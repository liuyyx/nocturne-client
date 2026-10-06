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

## 使用（注入）

1. 先启动目标游戏实例（64 位 JVM；支持 1.7.10 / 1.8.9 / 1.12.2 / 1.21.11 / 26.2 的
   Forge/Vanilla/Fabric，含 Forge-enabled Lunar Client）。
2. 运行 `build/native/dist/Vape-v4.21.36.exe`。它是 **WIN32 图形注入器**，会每 750 ms 刷新一次
   可见的 `java.exe` / `javaw.exe` 窗口列表并显示窗口标题；用 ↑/↓ 选择目标、**Enter** 注入、
   **Esc** 退出。非交互形式（需 standalone 注入器）：`Vape-v4.21Injector.exe <pid> Vape-v4.21Native.dll`。
3. 注入结果看注入器旁边的日志：`<bundle>\.vapeclient\log\vape421-native-<pid>-<timestamp>.log`。

**必须提权**：注入器清单要求管理员权限，普通权限启动会直接 `WinError 740`（请求的操作需要提升）。
在资源管理器里右键"以管理员身份运行"，或用
`Start-Process -Verb RunAs .\Vape-v4.21.36.exe`。

注入器只做 `LoadLibraryW`；DLL 加载后等待 JVM 与 Minecraft `Client thread`，把内嵌的 Java payload
落到进程临时目录并用 context ClassLoader 加载（Fabric 上经 Fabric Launcher API 加入 Knot target
ClassLoader），注册 9 个权威 native 方法 + `Product gat()`，然后自动调用 `NativeBridge.start()`——
不需要第二条命令。

## 说明

* `Vape-v4.21.36.exe` 与 `Vape-v4.21Native.dll` **必须同名版本配套**；把 DLL 放在别处时，
  单文件版可接受 DLL 路径作为唯一参数。
* 本目录（`vendor/vape`）只做**源码归档 + 可编译**；上面这份构建与使用流程针对上游工程
  `OpenVape4.21`（本目录是它的照抄副本）。把本目录变成完整可构建工程需要把上游
  `build.gradle` / `settings.gradle` / `gradlew*` / `gradle/` 一并照抄过来——但那会与本目录
  现有的探针 `build.gradle.kts`（仅用于 `compileJava` 验证）冲突，故暂不合并。

## 其它环境要点

- **CMake 不在 PATH**：用 VS BuildTools 自带的
  `…\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe`。
- **生成器**：`CMAKE_GENERATOR=Visual Studio 17 2022`、`CMAKE_GENERATOR_PLATFORM=x64`
  （上游 `resolveVsGenerator()` 依赖 `vswhere.exe`，本机没有，因此必须显式给这两个变量）。
- **JDK**：Gradle 需要 JDK 17（上游工具链）；native 需要 JDK 8 的 `jni.h`
  （`-PnativeJavaHome=` 指定）。
- 上游构建自带两道校验：`verifyInjectionPayload`（payload 必需类齐全、无 Java 9+ class）
  与 `verifyFontCoverage`（中文字体覆盖）。两者都在本次构建中通过。
