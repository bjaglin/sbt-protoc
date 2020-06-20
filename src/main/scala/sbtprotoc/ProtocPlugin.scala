package sbtprotoc

// avoid ambiguous import when sjsonnew.CollectionFormats.seqFormat is brought in scope by ignoring sbt 1.3 implicit
// https://github.com/sbt/sbt/commit/33194233698ab49682d89091a332edb808cb75bc#diff-cafb18f4282f4fff753f9efb35169d46R39
import sbt.{fileJsonFormatter => _, _}
import Keys._
import java.io.File

import protocbridge.{DescriptorSetGenerator, Target}
import sbt.librarymanagement.{CrossVersion, ModuleID}
import sbt.plugins.JvmPlugin
import sbt.util.CacheImplicits
import sjsonnew.JsonFormat

import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.platformDepsCrossVersion
import java.net.URLClassLoader
import scala.collection.mutable
import sbt.librarymanagement.DependencyResolution

object ProtocPlugin extends AutoPlugin {
  object autoImport {
    object PB {
      val includePaths = SettingKey[Seq[File]](
        "protoc-include-paths",
        "The paths that contain *.proto dependencies."
      )
      val additionalDependencies = SettingKey[Seq[ModuleID]](
        "additional-dependencies",
        "Additional dependencies to be added to library dependencies."
      )
      val externalIncludePath = SettingKey[File](
        "protoc-external-include-path",
        "The path to which protobuf:libraryDependencies are extracted and which is used as protobuf:includePath for protoc"
      )
      val externalSourcePath = SettingKey[File](
        "protoc-external-source-path",
        "The path to which protobuf-src:libraryDependencies are extracted and which is used as additional sources for protoc"
      )
      val generate = TaskKey[Seq[File]]("protoc-generate", "Compile the protobuf sources.")
      val unpackDependencies =
        TaskKey[UnpackedDependencies]("protoc-unpack-dependencies", "Unpack dependencies.")

      val protocOptions =
        SettingKey[Seq[String]]("protoc-options", "Additional options to be passed to protoc")
      val protoSources =
        SettingKey[Seq[File]]("protoc-sources", "Directories to look for source files")
      val targets = SettingKey[Seq[Target]]("protoc-targets", "List of targets to generate")

      val runProtoc = SettingKey[Seq[String] => Int](
        "protoc-run-protoc",
        "A function that executes the protobuf compiler with the given arguments, returning the exit code of the compilation run."
      )
      val protocVersion = SettingKey[String]("protoc-version", "Version flag to pass to protoc-jar")

      val pythonExe = SettingKey[String](
        "python-executable",
        "Full path for a Python.exe (deprecated and ignored)"
      )

      val deleteTargetDirectory = SettingKey[Boolean](
        "delete-target-directory",
        "Delete target directory before regenerating sources."
      )
      val recompile = TaskKey[Boolean]("protoc-recompile")

      val Target       = protocbridge.Target
      val gens         = protocbridge.gens
      val ProtocPlugin = "protoc-plugin"
    }

    implicit class AsProtocPlugin(val moduleId: ModuleID) extends AnyVal {
      def asProtocPlugin(): ModuleID = {
        moduleId % "protobuf" artifacts (Artifact(
          name = moduleId.name,
          `type` = PB.ProtocPlugin,
          extension = "exe",
          classifier = SystemDetector.detectedClassifier()
        ))
      }
    }
  }

  // internal key for detect change options
  private[this] val arguments = TaskKey[Arguments]("protoc-arguments")

  private[sbtprotoc] final case class Arguments(
      includePaths: Seq[File],
      protocOptions: Seq[String],
      deleteTargetDirectory: Boolean,
      targets: Seq[(String, Seq[protocbridge.Artifact], File, Seq[String])]
  )

  private[sbtprotoc] object Arguments extends CacheImplicits {
    implicit val artifactFormat: JsonFormat[protocbridge.Artifact] =
      caseClassArray(protocbridge.Artifact.apply _, protocbridge.Artifact.unapply _)

    implicit val argumentsFormat: JsonFormat[Arguments] =
      caseClassArray(Arguments.apply _, Arguments.unapply _)
  }

  import autoImport.PB

  val ProtobufConfig = config("protobuf")

  val ProtobufSrcConfig = config("protobuf-src")

  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins = JvmPlugin

  override def projectConfigurations: Seq[Configuration] = Seq(ProtobufConfig)

  def protobufGlobalSettings: Seq[Def.Setting[_]] =
    Seq(
      includeFilter in PB.generate := "*.proto",
      PB.externalIncludePath := target.value / "protobuf_external",
      PB.externalSourcePath := target.value / "protobuf_external_src",
      PB.unpackDependencies := unpackDependenciesTask(PB.unpackDependencies).value,
      PB.additionalDependencies := {
        val libs = (PB.targets in Compile).value.flatMap(_.generator.suggestedDependencies)
        platformDepsCrossVersion.?.value match {
          case Some(c) =>
            libs.map { lib =>
              val a = makeArtifact(lib)
              if (lib.crossVersion)
                a cross c
              else
                a
            }
          case None =>
            libs.map(makeArtifact)
        }
      },
      libraryDependencies ++= PB.additionalDependencies.value,
      classpathTypes in ProtobufConfig += PB.ProtocPlugin,
      managedClasspath in ProtobufConfig :=
        Classpaths.managedJars(
          ProtobufConfig,
          (classpathTypes in ProtobufConfig).value,
          (update in ProtobufConfig).value
        ),
      managedClasspath in ProtobufSrcConfig :=
        Classpaths.managedJars(
          ProtobufSrcConfig,
          (classpathTypes in ProtobufSrcConfig).value,
          (update in ProtobufSrcConfig).value
        ),
      ivyConfigurations ++= Seq(ProtobufConfig, ProtobufSrcConfig),
      PB.protocVersion := "-v3.11.4",
      PB.pythonExe := "python",
      PB.deleteTargetDirectory := true
    )

  // Settings that are applied at configuration (Compile, Test) scope.
  def protobufConfigSettings: Seq[Setting[_]] =
    Seq(
      arguments := Arguments(
        includePaths = PB.includePaths.value,
        protocOptions = PB.protocOptions.value,
        deleteTargetDirectory = PB.deleteTargetDirectory.value,
        targets = PB.targets.value.map { target =>
          (
            target.generator.name,
            target.generator.suggestedDependencies,
            target.outputPath,
            target.options
          )
        }
      ),
      PB.recompile := {
        arguments.previous.exists(_ != arguments.value)
      },
      PB.protocOptions := Nil,
      PB.protoSources := Nil,
      PB.protoSources += sourceDirectory.value / "protobuf",
      PB.protoSources += PB.externalSourcePath.value,
      PB.includePaths := (
        PB.includePaths.?.value.getOrElse(Nil) ++
          PB.protoSources.value ++
          protocIncludeDependencies.value :+
          PB.externalIncludePath.value,
      ).distinct,
      PB.targets := Nil,
      dependencyResolution in PB.generate := {
        val log = streams.value.log

        val rootDependencyResolution =
          (dependencyResolution in LocalRootProject).?.value

        rootDependencyResolution.getOrElse {
          log.warn(
            "Falling back on a default `dependencyResolution` to " +
              "resolve sbt-protoc plugins because `dependencyResolution` " +
              "is not set in the root project of this build."
          )
          log.warn(
            "Consider explicitly setting " +
              "`Global / PB.generate / dependencyResolution` " +
              "instead of relying on the default."
          )

          import sbt.librarymanagement.ivy._
          val ivyConfig = InlineIvyConfiguration()
            .withResolvers(Vector(Resolver.defaultLocal, Resolver.mavenCentral))
            .withLog(log)
          IvyDependencyResolution(ivyConfig)
        }

      },
      PB.generate := sourceGeneratorTask(PB.generate)
        .dependsOn(
          // We need to unpack dependencies for all subprojects since current project is allowed to import
          // them.
          PB.unpackDependencies.?.all(
            ScopeFilter(
              inDependencies(ThisProject, transitive = false),
              inConfigurations(Compile)
            )
          )
        )
        .value,
      PB.runProtoc := { args =>
        com.github.os72.protocjar.Protoc.runProtoc(PB.protocVersion.value +: args.toArray)
      },
      sourceGenerators += PB.generate
        .map(_.filter { file =>
          val name = file.getName
          name.endsWith(".java") || name.endsWith(".scala")
        })
        .taskValue
    )

  override def projectSettings: Seq[Def.Setting[_]] =
    protobufGlobalSettings ++ inConfig(Compile)(protobufConfigSettings) ++
      inConfig(Test)(protobufConfigSettings)

  case class UnpackedDependencies(mappedFiles: Map[File, Seq[File]]) {
    def files: Seq[File] = mappedFiles.values.flatten.toSeq
  }

  private[this] def executeProtoc(
      protocCommand: Seq[String] => Int,
      schemas: Set[File],
      includePaths: Seq[File],
      protocOptions: Seq[String],
      targets: Seq[Target],
      sandboxedLoader: protocbridge.Artifact => ClassLoader
  ): Int =
    try {
      val incPath = includePaths.map("-I" + _.getAbsolutePath)
      protocbridge.ProtocBridge.run(
        protocCommand,
        targets,
        incPath ++ protocOptions ++ schemas.map(_.getAbsolutePath),
        pluginFrontend = protocbridge.frontend.PluginFrontend.newInstance,
        classLoader = sandboxedLoader
      )
    } catch {
      case e: Exception =>
        throw new RuntimeException(
          "error occurred while compiling protobuf files: %s" format (e.getMessage),
          e
        )
    }

  private[this] val classLoaderMap = new mutable.WeakHashMap[protocbridge.Artifact, ClassLoader]

  private[this] def sandboxedLoader(lm: DependencyResolution, directory: File, log: Logger)(
      artifact: protocbridge.Artifact
  ): ClassLoader =
    classLoaderMap.synchronized {
      if (classLoaderMap.contains(artifact)) classLoaderMap(artifact)
      else {
        val modules = lm
          .retrieve(
            lm.wrapDependencyInModule(makeArtifact(artifact)),
            directory,
            log
          )
          .fold(w => throw w.resolveException, identity(_))

        val files = modules.map(_.toURI().toURL()).toArray
        val cloader = new URLClassLoader(
          files,
          new FilteringClassLoader(getClass().getClassLoader())
        )
        classLoaderMap.put(artifact, cloader)
        cloader
      }
    }

  private[this] def compile(
      protocCommand: Seq[String] => Int,
      schemas: Set[File],
      includePaths: Seq[File],
      protocOptions: Seq[String],
      targets: Seq[Target],
      deleteTargetDirectory: Boolean,
      log: Logger,
      sandboxedLoader: protocbridge.Artifact => ClassLoader
  ) = {
    val targetPaths = targets.map(_.outputPath).toSet

    if (deleteTargetDirectory) {
      targetPaths.foreach(IO.delete)
    }

    targets
      .map {
        case Target(DescriptorSetGenerator(), outputFile, _) => outputFile.getParentFile
        case Target(_, outputDirectory, _)                   => outputDirectory
      }
      .toSet[File]
      .foreach(_.mkdirs)

    if (schemas.nonEmpty && targets.nonEmpty) {
      log.info(
        "Compiling %d protobuf files to %s".format(schemas.size, targetPaths.mkString(","))
      )
      log.debug("protoc options:")
      protocOptions.map("\t" + _).foreach(log.debug(_))
      schemas.foreach(schema => log.info("Compiling schema %s" format schema))

      val exitCode =
        executeProtoc(protocCommand, schemas, includePaths, protocOptions, targets, sandboxedLoader)
      if (exitCode != 0)
        sys.error("protoc returned exit code: %d" format exitCode)

      targets.flatMap {
        case Target(DescriptorSetGenerator(), outputFile, _) => Seq(outputFile)
        case Target(_, outputDirectory, _)                   => outputDirectory.allPaths.get
      }.toSet
    } else if (schemas.nonEmpty && targets.isEmpty) {
      log.info("Protobufs files found, but PB.targets is empty.")
      Set[File]()
    } else {
      Set[File]()
    }
  }

  private[this] def unpack(
      deps: Seq[File],
      extractTarget: File,
      streams: TaskStreams
  ): Seq[(File, Seq[File])] = {
    def cachedExtractDep(dep: File): Seq[File] = {
      val cached = FileFunction.cached(
        streams.cacheDirectory / dep.name,
        inStyle = FilesInfo.lastModified,
        outStyle = FilesInfo.exists
      ) { deps =>
        IO.createDirectory(extractTarget)
        deps.flatMap { dep =>
          val set = IO.unzip(dep, extractTarget, "*.proto")
          if (set.nonEmpty)
            streams.log.debug("Extracted from " + dep + set.mkString(":\n * ", "\n * ", ""))
          set
        }
      }
      cached(Set(dep)).toSeq
    }

    deps.map { dep => dep -> cachedExtractDep(dep) }
  }

  private[this] def isNativePlugin(dep: Attributed[File]): Boolean =
    dep.get(artifact.key).exists(_.`type` == PB.ProtocPlugin)

  private[this] def sourceGeneratorTask(key: TaskKey[Seq[File]]): Def.Initialize[Task[Seq[File]]] =
    Def.task {
      val toInclude = (includeFilter in key).value
      val toExclude = (excludeFilter in key).value
      val schemas = (PB.protoSources in key).value
        .toSet[File]
        .flatMap(srcDir =>
          (srcDir ** (toInclude -- toExclude)).get
            .map(_.getAbsoluteFile)
        )
      // Include Scala binary version like "_2.11" for cross building.
      val cacheFile =
        (streams in key).value.cacheDirectory / s"protobuf_${scalaBinaryVersion.value}"

      val nativePlugins =
        (managedClasspath in (ProtobufConfig, key)).value.filter(isNativePlugin _)

      // Ensure all plugins are executable
      nativePlugins.foreach { dep => dep.data.setExecutable(true) }

      val nativePluginsArgs = nativePlugins.map { a =>
        val dep = a.get(artifact.key).get
        s"--plugin=${dep.name}=${a.data.absolutePath}"
      }

      def compileProto(): Set[File] =
        compile(
          (PB.runProtoc in key).value,
          schemas,
          (PB.includePaths in key).value,
          (PB.protocOptions in key).value ++ nativePluginsArgs,
          (PB.targets in key).value,
          (PB.deleteTargetDirectory in key).value,
          (streams in key).value.log,
          sandboxedLoader(
            (dependencyResolution in key).value,
            (streams in key).value.cacheDirectory / "sbt-protoc",
            (streams in key).value.log
          )
        )

      val cachedCompile = FileFunction.cached(
        cacheFile,
        inStyle = FilesInfo.lastModified,
        outStyle = FilesInfo.exists
      ) { (in: Set[File]) => compileProto() }

      if (PB.recompile.value) {
        compileProto().toSeq
      } else {
        cachedCompile(schemas).toSeq
      }
    }

  private[this] def unpackDependenciesTask(key: TaskKey[UnpackedDependencies]) =
    Def.task {
      // unpack() creates those dirs when there are jars to unpack, but not when there is
      // nothing to unpack. This leads to a protoc warning. See #152
      Seq(PB.externalSourcePath.value, PB.externalIncludePath.value).foreach(_.mkdirs)

      val extractedFiles = unpack(
        (managedClasspath in (ProtobufConfig, key)).value.map(_.data),
        (PB.externalIncludePath in key).value,
        (streams in key).value
      )
      val extractedSrcFiles = unpack(
        (managedClasspath in (ProtobufSrcConfig, key)).value.map(_.data),
        (PB.externalSourcePath in key).value,
        (streams in key).value
      )
      UnpackedDependencies((extractedFiles ++ extractedSrcFiles).toMap)
    }

  def protocIncludeDependencies: Def.Initialize[Seq[File]] =
    Def.setting {
      def filter =
        ScopeFilter(inDependencies(ThisProject, includeRoot = false), inConfigurations(Compile))
      (
        PB.protoSources.?.all(filter).value.map(_.getOrElse(Nil)).flatten ++
          PB.includePaths.?.all(filter).value.map(_.getOrElse(Nil)).flatten
      ).distinct
    }

  private[this] def makeArtifact(f: protocbridge.Artifact): ModuleID = {
    ModuleID(f.groupId, f.artifactId, f.version)
      .cross(if (f.crossVersion) CrossVersion.binary else Disabled)
  }

}
