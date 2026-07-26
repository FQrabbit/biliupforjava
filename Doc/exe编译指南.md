# Windows EXE 编译指南

本项目可以通过 GraalVM Native Image 编译成 Windows `.exe`。编译后的程序启动更快，也不要求目标电脑单独安装 Java。

> 已经装好 GraalVM、Maven 和 Visual Studio C++ 工具？直接从“快速编译”开始。

---

## 快速编译

### 最省事：直接双击脚本

项目根目录已经提供：

```text
build-windows-exe.bat
```

直接双击即可。脚本会自动完成以下操作：

- 切换到项目根目录
- 查找本机安装的 Visual Studio C++ 工具
- 初始化 x64 编译环境
- 检查 Maven 和 GraalVM
- 编译图标与版本信息
- 执行 Native Image 编译

为了避免不同 Windows 系统代码页把 BAT 中的中文拆成乱码，脚本窗口使用英文提示，但每一步都有明确编号和错误信息。

编译成功后，EXE 位于：

```text
target\biliupforjava.exe
```

如果脚本无法找到环境，窗口中会直接显示缺少的组件。下面的手动步骤可以用于检查具体问题。

### 手动编译

#### 1. 打开正确的命令行

在开始菜单搜索并打开：

```text
x64 Native Tools Command Prompt for VS 2022
```

这个命令行会自动准备 `cl.exe`、`rc.exe` 等 Windows 编译工具。普通 CMD 或 PowerShell 也能用，但需要先手动加载 Visual Studio 编译环境。

#### 2. 进入项目目录并检查环境

```cmd
cd /d 你的项目目录
mvn -version
native-image --version
```

重点看 `mvn -version` 的 Java 信息，应该显示 GraalVM 17，而不是普通 OpenJDK。

#### 3. 编译图标和版本信息

```cmd
rc /nologo /fo app.res app.rc
```

执行成功后，项目根目录会生成 `app.res`。当前 `pom.xml` 会在生成 EXE 时链接这个文件，因此不能跳过这一步。

#### 4. 编译 EXE

```cmd
mvn clean -Pnative native:compile -DskipTests -Dnative.maven.plugin.version=0.9.28
```

编译通常需要几分钟，期间 CPU 和内存占用较高是正常现象。成功后可以在这里找到程序：

```text
target\biliupforjava.exe
```

如果不想手动打开 Native Tools 命令行，也可以直接调用 Visual Studio 的初始化脚本。下面以 Visual Studio 2022 Community 为例：

```cmd
cmd.exe /c "call ""C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"" && rc /nologo /fo app.res app.rc && mvn clean -Pnative native:compile -DskipTests -Dnative.maven.plugin.version=0.9.28"
```

如果安装的是 Build Tools、Professional 或 Enterprise 版本，需要相应调整路径。

---

## 第一次编译前需要安装什么

### Visual Studio C++ 编译工具

1. 下载并运行 [Visual Studio Installer](https://visualstudio.microsoft.com/zh-hans/downloads/)。
2. 安装“使用 C++ 的桌面开发”工作负载。
3. 确认包含以下组件：
   - MSVC C++ x64/x86 生成工具
   - Windows 10 SDK 或 Windows 11 SDK

完整安装 Visual Studio 和只安装 Build Tools 都可以，关键是系统中能够使用 `cl.exe`、`link.exe` 和 `rc.exe`。

### GraalVM 17

1. 安装 GraalVM JDK 17，例如 Oracle GraalVM 或 GraalVM Community Edition。
2. 将 `JAVA_HOME` 指向 GraalVM 目录。
3. 将 `%JAVA_HOME%\bin` 加入 `Path`。
4. 重新打开命令行并检查：

```cmd
java -version
native-image --version
```

部分较新的 GraalVM 已经自带 `native-image`。如果找不到该命令，并且安装目录中存在 `gu`，再执行：

```cmd
gu install native-image
```

### Maven

安装 Maven 后执行：

```cmd
mvn -version
```

这里显示的 Java 路径必须是 GraalVM 17。如果仍然指向其他 JDK，先修正 `JAVA_HOME`，再重新打开命令行。

---

## 编译产物怎么打包

目前生成的 `biliupforjava.exe` 可以独立启动，现有用户只使用单个 EXE 也没有发现明确问题。如果你只在自己熟悉的环境中使用，继续保留单 EXE 没有问题。

不过，项目使用了图片和 AWT 相关功能，GraalVM 仍可能生成一些用于特定功能的 JDK DLL，例如：

```text
awt.dll
jaas.dll
javaaccessbridge.dll
javajpeg.dll
jawt.dll
lcms.dll
w2k_lsa_auth.dll
```

不同 GraalVM 小版本生成的 DLL 可能略有差异。例如 Oracle GraalVM 17.0.12 还会生成 `java.dll` 和 `jvm.dll`。实际打包时，以本次编译后 `target` 根目录中的全部 `.dll` 文件为准；Native Image 结束前也会在 `Produced artifacts` 中打印完整列表。

```text
target\biliupforjava.exe
target\*.dll
```

这些 DLL 不会在程序启动时全部加载，因此单独运行 EXE 通常不会报错。为了让公开发布包尽量兼容不同 Windows 环境，建议将 EXE 和本次生成的全部 DLL 放在同一个目录，再一起压缩成 ZIP。推荐结构如下：

```text
biliupforjava-windows-x64\
├─ biliupforjava.exe
├─ awt.dll
├─ javajpeg.dll
├─ lcms.dll
└─ 其他本次生成的 DLL
```

GitHub Actions 会自动生成并上传类似下面的压缩包：

```text
biliupforjava-1.4.1-beta7.8-Windows.zip
```

压缩包中的 EXE 会命名为 `biliupforjava-1.4.1-beta7.8.exe`，其余 DLL 保持原文件名。`-Windows` 表示这个 ZIP 只能在 Windows x64 环境中使用。

如果你仍想给用户提供单个 EXE，也可以继续保留；ZIP 主要作为包含全部 GraalVM 运行产物的稳妥版本。

---

## 第一次运行

1. 如果下载的是 Windows ZIP，将压缩包完整解压；如果使用单个 EXE，可以直接运行。
2. 直接运行 `biliupforjava.exe`。
3. 如果当前目录没有配置文件，程序会进入初始化向导，并在控制台显示实际访问地址。
4. 默认会从 `8080` 端口开始尝试，通常访问：

```text
http://localhost:8080/html/setup.html
```

5. 保存配置后，同级目录会生成 `application.yml`，程序随后退出。
6. 再次运行 EXE，程序会读取刚刚保存的配置并正常启动。

如果 8080 已被占用，以控制台实际显示的向导地址为准。

---

<details>
<summary><strong>开发者：什么时候需要重新收集 Native Image 配置？</strong></summary>

Native Image 会在编译时分析程序真正用到的代码。反射、动态代理、JNI 和部分资源文件无法完全依靠静态分析发现，需要通过 `META-INF/native-image` 下的配置进行补充。

仓库目前已经包含一套配置。普通编译不需要每次重新生成，只有在以下情况下才考虑运行 Tracing Agent：

- 新增大量反射调用或动态代理
- 引入新的框架或原生库
- JVM 版本运行正常，但 EXE 中出现类、构造方法、资源文件找不到等问题

先编译普通 WAR：

```cmd
mvn clean package -DskipTests
```

再将 Agent 输出到临时目录：

```cmd
java -agentlib:native-image-agent=config-output-dir=target/native-agent -jar target/biliLiveRecord-0.0.1-SNAPSHOT.war
```

程序运行后，尽量覆盖受影响的功能，例如登录、新增房间、Webhook、上传、图片处理和 WebSocket。测试完成后按 `Ctrl+C` 正常退出。

Agent 生成的 JSON 位于：

```text
target\native-agent
```

不要直接覆盖仓库中现有的配置。先检查差异，再把确实需要的内容合并到：

```text
src\main\resources\META-INF\native-image
```

</details>

---

## 常见问题

### `rc`、`cl` 或 `link` 不是内部或外部命令

当前终端没有加载 Visual Studio C++ 环境。请打开 `x64 Native Tools Command Prompt for VS 2022`，或者先调用对应版本的 `vcvars64.bat`。

### Maven 使用的不是 GraalVM

运行：

```cmd
mvn -version
```

如果 Java 路径不是 GraalVM 17，检查 `JAVA_HOME` 和 `Path`，修改后重新打开命令行。

### 找不到 `native-image`

先确认当前使用的是 GraalVM。较旧版本可能还需要执行：

```cmd
gu install native-image
```

### 找不到 `app.res`

重新执行：

```cmd
rc /nologo /fo app.res app.rc
```

同时确认项目根目录中存在 `app.rc` 和 `icon.ico`。

### 编译很慢或占用大量内存

这是 Native Image 的正常现象。建议关闭不需要的程序，并为编译保留至少 8 GB 可用内存。不要在编译过程中强制关闭 Maven 或 Java 进程。

### EXE 在其他电脑上无法启动或提示缺少 DLL

单个 EXE 在当前验证中可以正常运行。如果某台电脑或某项图片功能提示缺少 DLL，把编译后 `target` 根目录中的全部 DLL 与 EXE 放在同一目录；也可以直接改用 GitHub Release 中带 `-Windows` 标识的完整 ZIP。

### 脚本显示 `BUILD COMPLETED WITH WARNINGS`

部分较旧的 GraalVM 可能在 EXE 和 DLL 已经全部生成后仍返回非零退出码。脚本会再次核对 EXE 和项目需要的核心 DLL；只有这些产物存在且不是空文件时才会显示这个提示。建议先测试生成的 EXE，并在方便时升级 GraalVM。

### JVM 版本正常，EXE 中某个功能报类或资源缺失

这通常与反射、动态代理或资源配置有关。参考上面的“开发者：什么时候需要重新收集 Native Image 配置？”，使用 Tracing Agent 复现相关功能并检查生成的配置。
