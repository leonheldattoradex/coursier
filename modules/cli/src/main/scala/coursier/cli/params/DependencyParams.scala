package coursier.cli.params

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.implicits._
import coursier.cli.options.DependencyOptions
import coursier.cli.util.DeprecatedModuleRequirements0
import coursier.core._
import coursier.install.ScalaPlatform
import coursier.parse.{DependencyParser, JavaOrScalaDependency, JavaOrScalaModule, ModuleParser}

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

import scala.io.Source
import scala.util.control.NonFatal

final case class DependencyParams(
  exclude: Set[JavaOrScalaModule],
  perModuleExclude: Map[JavaOrScalaModule, Set[JavaOrScalaModule]], // FIXME key should be Module
  intransitiveDependencies: Seq[(JavaOrScalaDependency, Map[String, String])],
  sbtPluginDependencies: Seq[(JavaOrScalaDependency, Map[String, String])],
  fromFilesDependencies: Seq[String],
  platformOpt: Option[ScalaPlatform],
  bomDependencies: Seq[Dependency]
) {
  def native: Boolean =
    platformOpt match {
      case Some(ScalaPlatform.Native) => true
      case _                          => false
    }
}

object DependencyParams {
  def apply(
    options: DependencyOptions,
    forcedScalaVersionOpt: Option[String]
  ): ValidatedNel[String, DependencyParams] = {

    val excludeV =
      ModuleParser.javaOrScalaModules(options.exclude).either match {
        case Left(errors) =>
          Validated.invalidNel(
            s"Cannot parse excluded modules:" + System.lineSeparator() +
              errors
                .map("  " + _)
                .mkString(System.lineSeparator())
          )

        case Right(excludes0) =>
          val (excludesNoAttr, excludesWithAttr) = excludes0.partition(_.attributes.isEmpty)

          if (excludesWithAttr.isEmpty)
            Validated.validNel(
              excludesNoAttr
                .toSet
            )
          else
            Validated.invalidNel(
              s"Excluded modules with attributes not supported:" + System.lineSeparator() +
                excludesWithAttr
                  .map("  " + _)
                  .mkString(System.lineSeparator())
            )
      }

    val fromFilesDependencies: Seq[String] =
      options.dependencyFile
        .toVector
        .flatMap { file =>
          try {
            val content = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8)
            content
              .linesIterator
              .map(_.trim)
              .filter(_.nonEmpty)
              .toVector
          }
          catch {
            case NonFatal(e) =>
              throw new Exception(s"Error reading dependencies from $file", e)
          }
        }

    val perModuleExcludeV: ValidatedNel[String, Map[JavaOrScalaModule, Set[JavaOrScalaModule]]] =
      if (options.localExcludeFile.isEmpty)
        Validated.validNel(Map.empty[JavaOrScalaModule, Set[JavaOrScalaModule]])
      else {

        // meh, I/O

        val source = Source.fromFile(options.localExcludeFile) // default codec...
        val lines =
          try source.mkString.split("\n")
          finally source.close()

        lines
          .toList
          .traverse { str =>
            val parent_and_child = str.split("--")
            if (parent_and_child.length != 2)
              Validated.invalidNel(s"Failed to parse $str")
            else {
              val child          = parent_and_child(1)
              val child_org_name = child.split(":")
              if (child_org_name.length != 2)
                Validated.invalidNel(s"Failed to parse $child")
              else
                Validated.fromEither(
                  ModuleParser.javaOrScalaModule(parent_and_child(0)).left.map(NonEmptyList.one)
                ).map { from =>
                  // accept scala modules too?
                  val mod0 = Module(
                    Organization(child_org_name(0)),
                    ModuleName(child_org_name(1)),
                    Map()
                  )
                  val mod: JavaOrScalaModule = JavaOrScalaModule.JavaModule(mod0)
                  (from, mod)
                }
            }
          }
          .map { list =>
            list
              .groupBy(_._1)
              .view.mapValues(_.map(_._2).toSet)
              .iterator
              .toMap
          }
      }

    val moduleReqV = (excludeV, perModuleExcludeV).mapN {
      (exclude, perModuleExclude) =>
        DeprecatedModuleRequirements0(exclude, perModuleExclude)
    }

    val intransitiveDependenciesV = moduleReqV
      .toEither
      .flatMap { moduleReq =>
        DependencyParser.javaOrScalaDependenciesParams(options.intransitive).either match {
          case Left(e) =>
            Left(
              NonEmptyList.one(
                s"Cannot parse intransitive dependencies:" + System.lineSeparator() +
                  e.map("  " + _).mkString(System.lineSeparator())
              )
            )
          case Right(l) =>
            Right(
              moduleReq(l.map { case (d, p) =>
                (d.withUnderlyingDependency(_.withTransitive(false)), p)
              })
            )
        }
      }
      .toValidated

    val sbtPluginDependenciesV =
      DependencyParser.javaOrScalaDependenciesParams(options.sbtPlugin).either match {
        case Left(e) =>
          Validated.invalidNel(
            s"Cannot parse sbt plugin dependencies:" + System.lineSeparator() +
              e.map("  " + _).mkString(System.lineSeparator())
          )

        case Right(Seq()) =>
          Validated.validNel(Nil)

        case Right(l0) =>
          val defaults = {
            val sbtVer = options.sbtVersion.split('.') match {
              case Array("1", _, _) =>
                // all sbt 1.x versions use 1.0 as short version
                "1.0"
              case arr => arr.take(2).mkString(".")
            }
            val scalaVer = forcedScalaVersionOpt
              .map(_.split('.').take(2).mkString("."))
              .getOrElse {
                sbtVer match {
                  case "0.13" => "2.10"
                  case "1.0"  => "2.12"
                  case _      => "2.12" // ???
                }
              }
            Map(
              "scalaVersion" -> scalaVer, // FIXME Apply later when we know the selected scala version?
              "sbtVersion" -> sbtVer
            )
          }
          val l = l0.map {
            case (dep, params) =>
              val dep0 = dep.withUnderlyingDependency { dep =>
                dep.withModule(
                  dep.module.withAttributes(defaults ++ dep.module.attributes)
                ) // dependency specific attributes override the default values
              }
              (dep0, params)
          }
          Validated.validNel(l)
      }

    val platformOptV = (options.scalaJs, options.native) match {
      case (false, false) => Validated.validNel(None)
      case (true, false)  => Validated.validNel(Some(ScalaPlatform.JS))
      case (false, true)  => Validated.validNel(Some(ScalaPlatform.Native))
      case (true, true)   => Validated.invalidNel("Cannot specify both --scala-js and --native")
    }

    val bomDependenciesV = {
      val either =
        DependencyParser.javaOrScalaDependenciesParams(options.bomDependencies).either match {
          case Left(e) =>
            Left(
              NonEmptyList.one(
                s"Cannot parse BOM dependencies:" + System.lineSeparator() +
                  e.map("  " + _).mkString(System.lineSeparator())
              )
            )
          case Right(deps) =>
            // FIXME params are ignored
            val scalaDeps = deps.zipWithIndex.collect {
              case ((_: JavaOrScalaDependency.ScalaDependency, _), idx) => idx
            }
            if (scalaDeps.isEmpty)
              // FIXME JavaOrScalaDependency.JavaDependency has an exclude field too, we shouldn't accept a non empty value for it
              Right(deps.collect { case (j: JavaOrScalaDependency.JavaDependency, _) =>
                j.dependency
              })
            else
              Left(
                NonEmptyList.one(
                  "Scala dependencies not accepted as BOM dependencies:" + System.lineSeparator() +
                    scalaDeps.map(i => options.bomDependencies(i)).map("  " + _).mkString(
                      System.lineSeparator()
                    )
                )
              )
        }
      either.toValidated
    }

    (
      excludeV,
      perModuleExcludeV,
      intransitiveDependenciesV,
      sbtPluginDependenciesV,
      platformOptV,
      bomDependenciesV
    ).mapN {
      (
        exclude,
        perModuleExclude,
        intransitiveDependencies,
        sbtPluginDependencies,
        platformOpt,
        bomDependencies
      ) =>
        DependencyParams(
          exclude,
          perModuleExclude,
          intransitiveDependencies,
          sbtPluginDependencies,
          fromFilesDependencies,
          platformOpt,
          bomDependencies
        )
    }
  }
}
