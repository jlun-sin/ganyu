package update.services

import java.nio.file.Paths
import java.util.UUID

import cats.data.EitherT
import cats.syntax.all.*
import cats.{ Applicative, Monad }
import core.domain.project.ProjectScanConfigRepository
import core.domain.update.{ DependencyToUpdate, UpdateDependency }
import gitlab.{ Action, CommitAction, GitlabApi }
import jira.*
import org.legogroup.woof.{ *, given }
import parsers.python.{ PackageManagementFiles, Poetry, Requirements }
import update.domain.*
import core.domain.semver

private case class FileContent(filePath: String, content: String)

object UpdateService:
  def make[F[_]: Monad: Logger](
      repository: UpdateRepository[F],
      projectConfigRepository: ProjectScanConfigRepository[F],
      gitlabApi: GitlabApi[F],
      jiraNotificationService: JiraNotificationService[F],
      poetry: Poetry[F],
      requirements: Requirements
  ): UpdateService[F] = new:

    override def canUpdate(
        dependencies: List[DependencyToUpdate],
        projectId: UUID,
        sourceFile: String
    ): F[List[(DependencyToUpdate, Boolean)]] =
      val requests = dependencies.map: dependency =>
        UpdateRequest(projectId, dependency.name, dependency.latestVersion)
      repository.exist(requests).map: existingRequests =>
        canUpdate(dependencies, existingRequests, sourceFile)

    override def shouldUpdate(dependencies: List[DependencyToUpdate])
        : F[List[(DependencyToUpdate, Boolean)]] =
      dependencies.map(d => d -> UpdateService.shouldUpdate(d)).pure

    override def update(request: UpdateDependency): F[Either[String, Unit]] =
      projectConfigRepository
        .findByProjectName(request.projectName)
        .flatMap:
          case None => "Config for project not found".asLeft.pure
          case Some(config) =>
            val req = UpdateDependencyDetails(
              projectId = config.project.id,
              projectName = config.project.name,
              projectBranch = config.branch,
              projectGitlabId = config.project.repositoryId,
              filePath = request.filePath,
              dependencyName = request.dependencyName,
              fromVersion = request.fromVersion,
              toVersion = request.toVersion
            )
            update(req)

    override def update(request: UpdateDependencyDetails)
        : F[Either[String, Unit]] =
      Logger[F].info(s"Requested update for ${request}") *> (
        FileType.fromPath(request.filePath) match
          case Left(reason) => reason.asLeft.pure
          case Right(fileType) =>
            EitherT(ensureAttemptWasNotMadeBefore(request))
              .flatMap: _ =>
                updateDependencyInDependencyManager(request, fileType)
              .flatMap: updatedContent =>
                EitherT(publishToGit(request, updatedContent))
              .flatTap: mergeRequest =>
                val attempt = UpdateAttempt(
                  request.projectId,
                  request.dependencyName,
                  request.toVersion,
                  mergeRequest.webUrl.toString
                )
                EitherT(repository.save(attempt).map(_.asRight))
              .flatTap: mergeRequest =>
                EitherT(
                  jiraNotificationService.notify(request, mergeRequest.webUrl)
                )
              .flatTap: _ =>
                EitherT(Logger[F].info("Done with update").map(_.asRight))
              .void
              .value
      )

    private def ensureAttemptWasNotMadeBefore(
        request: UpdateDependencyDetails
    ) =
      repository
        .exists(
          request.projectId,
          request.dependencyName,
          request.toVersion
        )
        .flatMap:
          case true  => "Update request already exists".asLeft.pure
          case false => ().asRight.pure

    private def publishToGit(
        request: UpdateDependencyDetails,
        contents: List[FileContent]
    ) =
      val newBranchName =
        s"ganyu-${request.dependencyName}-${request.toVersion}"
      val commitActions = contents.map: fileContent =>
        CommitAction(Action.Update, fileContent.filePath, fileContent.content)
      val commitMessage =
        s"Bumps ${request.dependencyName} from ${request.fromVersion} to ${request.toVersion}"
      val mergeRequestTitle = commitMessage

      gitlabApi.createBranch(
        request.projectGitlabId,
        request.projectBranch,
        newBranchName
      )
        *> gitlabApi.createCommit(
          request.projectGitlabId,
          newBranchName,
          commitMessage,
          commitActions
        )
        *> gitlabApi.createMergeRequest(
          request.projectGitlabId,
          newBranchName,
          request.projectBranch,
          mergeRequestTitle
        )

    private def updateDependencyInDependencyManager(
        request: UpdateDependencyDetails,
        fileType: FileType
    ) =
      fileType match
        case FileType.Txt  => updateDependencyInRequirements(request)
        case FileType.Toml => updateDependencyInPoetry(request)

    private def updateDependencyInRequirements(
        request: UpdateDependencyDetails
    ) =
      EitherT:
        getRequirementsTxtFromGit(
          request.projectGitlabId,
          request.projectBranch,
          request.filePath
        ).map: result =>
          result
            .flatMap: originalFile =>
              requirements
                .update(
                  request.dependencyName,
                  request.fromVersion,
                  request.toVersion,
                  originalFile
                )
                .map: updatedFile =>
                  FileContent(request.filePath, updatedFile.content) :: Nil
            .leftMap(_.toString)

    private def getRequirementsTxtFromGit(
        projectGitlabId: String,
        projectBranch: String,
        requirementsPath: String
    ): F[Either[String, PackageManagementFiles.RequirementFile]] =
      gitlabApi
        .getFileContent(projectGitlabId, projectBranch, requirementsPath)
        .map: result =>
          result.map: content =>
            PackageManagementFiles.RequirementFile(content)

    private def updateDependencyInPoetry(request: UpdateDependencyDetails) =
      val parent = Option(Paths.get(request.filePath).getParent)
      val pyProject = parent
        .map(_.resolve("pyproject.toml").toString)
        .getOrElse("pyproject.toml")
      val lock = parent
        .map(_.resolve("poetry.lock").toString)
        .getOrElse("poetry.lock")

      for
        originalFiles <- EitherT(getPoetryFilesFromGit(
          request.projectGitlabId,
          request.projectBranch,
          pyProject,
          lock
        ))
        updatedFiles <- EitherT(poetry.update(
          request.dependencyName,
          request.fromVersion,
          request.toVersion,
          originalFiles
        ).map(_.leftMap(_.toString)))
        commits =
          List(
            FileContent(pyProject, updatedFiles.pyProjectContent),
            FileContent(lock, updatedFiles.lockContent)
          )
      yield commits

    private def getPoetryFilesFromGit(
        projectGitlabId: String,
        projectBranch: String,
        pyProjectPath: String,
        lockPath: String
    ): F[Either[String, PackageManagementFiles.PoetryFiles]] =
      val getPyProject = gitlabApi.getFileContent(
        projectGitlabId,
        projectBranch,
        pyProjectPath
      )
      val getLock = gitlabApi.getFileContent(
        projectGitlabId,
        projectBranch,
        lockPath
      )

      (getPyProject, getLock).tupled.map: (pyProjectRes, lockRes) =>
        (pyProjectRes, lockRes).tupled.map: (pyProject, lock) =>
          PackageManagementFiles.PoetryFiles(pyProject, lock)

    private def canUpdate(
        dependencies: List[DependencyToUpdate],
        existingRequests: List[UpdateRequest],
        sourceFile: String
    ): List[(DependencyToUpdate, Boolean)] =
      dependencies.map: dependency =>
        val requestExists = existingRequests
          .find(_.dependencyName == dependency.name)
          .isDefined
        val canBeUpdated = dependency
          .currentVersion
          .map: currentVersion =>
            currentVersion != dependency.latestVersion
              && FileType.fromPath(sourceFile).isRight
          .getOrElse(false)
        (dependency, !requestExists && canBeUpdated)

  def shouldUpdate(dependency: DependencyToUpdate): Boolean =
    dependency.currentVersion.map: currentVersion =>
      val isHoled = semver.isHoled(currentVersion)
      val versionDifference = semver
        .unhole(currentVersion)
        .flatMap(semver.calculateVersionDifference(_, dependency.latestVersion))
      val hasVulnerabilities = dependency.vulnerabilities.nonEmpty
      val couldResolveVulnerabilities =
        semver.removeSymbol(currentVersion) != dependency.latestVersion

      (
        isHoled,
        versionDifference,
        hasVulnerabilities && couldResolveVulnerabilities
      ) match
        case (_, _, true)                                        => true
        case (_, None, false)                                    => false
        case (true, Some(semver.VersionDifference.Patch), false) => false
        case _                                                   => true
    .getOrElse(false)
