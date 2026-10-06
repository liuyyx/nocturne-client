# 从照抄源码重建 Vape 本体（B 路线，已实测）

本目录（`vendor/vape/`）是上游 `OpenVape 4.21.36` 的**原样照抄**（CC0-1.0）。
本文件记录"如何从这份源码重建出可运行的 Vape 产品"，命令与参数均为**本机实测通过**的版本。

## 结论（2026-10-07 实测）

| 产物 | 大小 | 说明 |
|---|---|---|
| `build/native/dist/Vape-v4.21.36.exe` | 35.4 MB | 单文件注入器（内嵌 DLL，RCDATA） |
| `build/native/dist/Vape-v4.21Native.dll` | 35.1 MB | JNI/JVMTI 桥 + 内嵌 Java payload |
| `build/libs/vape421-product-recovery-4.21.36-injection.jar` | 35.0 MB | `--release 8` 的 payload |
| `build/native/Release/Vape421BootstrapTest.exe` | 19 KB | native 侧自测 |

native 自测三种模式**全部通过**（`rc=0`）：

```
Vape421BootstrapTest.exe              # 本地 controller socket 握手 + token 交换 + 进度协议
Vape421BootstrapTest.exe standalone   # 独立模式返回哨兵 token "0"
Vape421BootstrapTest.exe invalid      # 畸形 bootstrap 被拒绝（状态置 FAILED）
```

## 构建步骤

在**上游工程目录**（`<工作区>/OpenVape4.21`，本目录是其照抄副本）执行：

```bat
set GRADLE_USER_HOME=<隔离的 gradle home>            :: 见下方"为什么"
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set PATH=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin;%PATH%
set CMAKE_GENERATOR=Visual Studio 17 2022
set CMAKE_GENERATOR_PLATFORM=x64
cd /d <工作区>\OpenVape4.21
call gradlew.bat prepareInjectionBundle -PtargetRelease=8 ^
  -PnativeJavaHome="C:\Program Files\Eclipse Adoptium\jdk-8.0.492.9-hotspot" --console=plain
```

实测耗时约 **5 分钟**（依赖已缓存时）。

## 为什么需要独立的 GRADLE_USER_HOME

上游 `settings.gradle` 设了：

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
```

而本机全局 `~/.gradle/init.d/mirror.gradle`（阿里云镜像）用 `allprojects { repositories { ... } }`
往**项目级**塞仓库 → Gradle 8.8 直接判失败：

```
Build was configured to prefer settings repositories over project repositories
but repository 'maven' was added by initialization script '...mirror.gradle'
```

规避方式（不改全局配置、也不改上游源码）：另建一个 `GRADLE_USER_HOME`，在其中放一个
**只在 settings 层**加镜像的 init 脚本：

```groovy
// <isolated-home>/init.d/aliyun-settings.gradle
settingsEvaluated { settings ->
    settings.pluginManagement.repositories {
        maven { url = uri('https://maven.aliyun.com/repository/gradle-plugin') }
    }
    settings.dependencyResolutionManagement.repositories {
        maven { url = uri('https://maven.aliyun.com/repository/public') }
    }
}
```

（已下载的 `gradle-8.8-bin` dists 可直接从原 home 复制过来，省一次下载。）

## 其它环境要点

- **CMake 不在 PATH**：用 VS BuildTools 自带的
  `…\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe`。
- **生成器**：`CMAKE_GENERATOR=Visual Studio 17 2022`、`CMAKE_GENERATOR_PLATFORM=x64`
  （上游 `resolveVsGenerator()` 依赖 `vswhere.exe`，本机没有，因此必须显式给这两个变量）。
- **JDK**：Gradle 需要 JDK 17（上游工具链）；native 需要 JDK 8 的 `jni.h`
  （`-PnativeJavaHome=` 指定）。
- 上游构建自带两道校验：`verifyInjectionPayload`（payload 必需类齐全、无 Java 9+ class）
  与 `verifyFontCoverage`（中文字体覆盖）。两者都在本次构建中通过。
