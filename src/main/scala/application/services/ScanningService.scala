package application.services

import scala.annotation.tailrec

import cats.*
import cats.effect.std.*
import cats.implicits.*
import domain.dependency.*
import domain.project.*
import domain.{ Exporter, Source }
import org.joda.time.DateTime
import org.legogroup.woof.{ *, given }

trait ScanningService[F[_]]:
  def scan(projects: List[Project]): F[Unit]

object ScanningService:
  def make[F[_]: Monad: Logger: Parallel, A](
      source: Source[F, A],
      prepareForSource: Project => Option[A],
      reporter: DependencyReporter[F],
      repository: ScanResultRepository[F]
  ): ScanningService[F] = new ScanningService[F]:

    override def scan(projects: List[Project]): F[Unit] =
      for
        _ <- Logger[F].info(
          s"Scanning dependencies of ${projects.length} projects..."
        )
        projectsDependencies <- projects
          .parTraverse(project =>
            prepareForSource(project)
              .map(source.extract(_))
              .getOrElse(Monad[F].pure(List.empty))
              .map(dependencies => ProjectDependencies(project, dependencies))
          )
        dependencies = projectsDependencies
          .flatMap(_.dependencies.flatMap(_.items))
        _ <- Logger[F].info(
          s"Checking the details of ${dependencies.length} dependencies..."
        )
        details <- reporter.getDetails(dependencies)
        _       <- Logger[F].info("Building the report...")
        detailsMap = buildDetailsMap(details)
        reports    = projectsDependencies.map(buildReport(detailsMap))
        _ <- repository.save(reports, DateTime.now())
      yield ()

  private val latestKey = "LATEST"

  private type DetailsMap = Map[String, Map[String, DependencyDetails]]

  private def getDetails(
      details: DetailsMap
  )(name: String, version: String): Option[DependencyDetails] =
    details.get(name).flatMap(_.get(version))

  private def buildDetailsMap(
      dependenciesDetails: List[DependencyDetails]
  ): DetailsMap =
    dependenciesDetails
      .groupBy(_.name)
      .map {
        case (name, details) =>
          val orderedByVersion = details.sortWith(_.ofVersion > _.ofVersion)
          val latest           = orderedByVersion.head.copy(ofVersion = latestKey)

          name -> (latest :: orderedByVersion).map(d => d.ofVersion -> d).toMap
      }
      .toMap

  private def buildReport(details: DetailsMap)(
      projectDependencies: ProjectDependencies
  ): ScanResult =
    val detailsOf = getDetails(details)

    @tailrec
    def inner(
        dependencies: List[Dependency],
        report: List[DependencyReport]
    ): List[DependencyReport] =
      dependencies match
        case head :: next =>
          detailsOf(head.name, head.currentVersion.getOrElse(latestKey)) match
            case Some(detail) =>
              val notes = detail.minLanguageVersion.map(version =>
                s"min. language version: $version"
              )

              inner(next, DependencyReport(head, detail, notes) :: report)

            case None => inner(next, report)

        case Nil => report

    val report = projectDependencies.dependencies.map(dependencyGroup =>
      Grouped(
        dependencyGroup.groupName,
        inner(dependencyGroup.items, List.empty)
      )
    )
    ScanResult(projectDependencies.project, report)
