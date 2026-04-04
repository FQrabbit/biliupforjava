# biliupforjava 编译可执行文件 (exe) 指南

本项目支持使用 GraalVM Native Image 将 Spring Boot Java 项目打包成独立运行的 Windows `.exe` 可执行文件。这不仅可以加快启动速度，还能大幅降低内存占用，且用户运行不需要预先安装 Java 环境。

## 1. 编译环境准备

在 Windows 下编译 Native Image，需要准备以下两个核心环境：

### 1.1 安装 Visual Studio Build Tools (C++ 编译环境)
GraalVM 需要用到微软的 C++ 编译器（MSVC）来将 Java 代码编译成机器码。
1. 下载并安装 [Visual Studio Installer](https://visualstudio.microsoft.com/zh-hans/downloads/)。
2. 在安装器中，勾选 **“使用 C++ 的桌面开发”**。
3. 在右侧的“安装详细信息”中，确保勾选了以下两项（默认通常已勾选）：
   - **MSVC v143 - VS 2022 C++ x64/x86 生成工具**
   - **Windows 11 SDK** (或 Windows 10 SDK)

### 1.2 安装 GraalVM JDK 和 native-image 工具
1. 下载带有 GraalVM 的 JDK 17（推荐使用 Oracle GraalVM 或 CE 版本）。
2. 解压到任意目录，并配置系统环境变量：
   - 新建系统变量 `JAVA_HOME`，值为 GraalVM 的解压目录（例如 `C:\graalvm-jdk-17`）。
   - 在系统变量 `Path` 中，添加 `%JAVA_HOME%\bin`。
3. **安装 native-image 组件**：
   - 右键“开始”菜单，以**管理员身份**打开 Windows 终端（CMD 或 PowerShell）。
   - 运行以下命令安装 Native Image 支持：
     ```cmd
     gu install native-image
     ```

---

## 2. 准备图标与版本信息资源 (可选)

如果你希望最终生成的 `biliupforjava.exe` 拥有自定义的图标和版本详细信息（例如右键属性中的“文件说明”、“产品版本”等），需要通过 Windows 资源编译器（`rc.exe`）将配置编译到执行文件中。

### 2.1 准备文件
在项目根目录下准备以下两个文件：
- `icon.ico`: 你的程序图标（必须是 `.ico` 格式，如果只有 svg/png 需要先转换）。
- `app.rc`: 资源描述文件，内容参考如下：

```rc
IDI_ICON1 ICON "icon.ico"

1 VERSIONINFO
FILEVERSION 1,4,1,71
PRODUCTVERSION 1,4,1,71
BEGIN
    BLOCK "StringFileInfo"
    BEGIN
        BLOCK "080404b0"
        BEGIN
            VALUE "CompanyName", "biliupforjava"
            VALUE "FileDescription", "BiliLive Record & Upload Tool"
            VALUE "FileVersion", "1.4.1.71"
            VALUE "InternalName", "biliupforjava"
            VALUE "OriginalFilename", "biliupforjava.exe"
            VALUE "ProductName", "biliupforjava"
            VALUE "ProductVersion", "1.4.1.71"
        END
    END
    BLOCK "VarFileInfo"
    BEGIN
        VALUE "Translation", 0x804, 1200
    END
END
```

### 2.2 编译为 `.res` 文件
在进行 Maven 编译前，需要先将 `app.rc` 编译为 `app.res`。
1. 从开始菜单中找到并打开 **x64 Native Tools Command Prompt for VS 2022**（这是 VS 提供的自带好 C++ 环境变量的终端）。
2. `cd` 切换到你的项目根目录。
3. 执行编译命令：
   ```cmd
   rc /nologo /fo app.res app.rc
   ```
执行成功后，项目目录下会生成一个 `app.res` 文件。

---

## 3. 开始编译 exe

完成环境配置和资源准备后，就可以开始编译了。

**重要提醒：** 
由于打包 exe 需要非常庞大的系统资源（CPU 满载，内存消耗 8GB 以上），且容易受到杀毒软件和 IDE 沙盒限制的干扰。
**请务必在独立的系统原生终端（x64 Native Tools Command Prompt for VS 2022）中执行以下命令，切勿在 IDE（如 Trae/IDEA）自带的终端中执行！**

1. 打开 **x64 Native Tools Command Prompt for VS 2022**，进入项目根目录。
2. 确认 `pom.xml` 中的 `<packaging>` 标签值为 `jar`（如果是 `war` 编译会失败）。
3. 运行 Maven 编译命令，并指定链接刚才生成的 `app.res` 资源文件：

```cmd
mvn clean -Pnative native:compile -DskipTests -Dnative.buildArgs="-H:NativeLinkerOption=app.res"
```

### 编译过程说明：
- 编译过程通常需要 **3 ~ 10 分钟** 不等，取决于 CPU 性能。
- 期间会看到大量的 `[graalvm reachability metadata repository]` 下载和解析日志，以及最后的 `[native-image]` 编译阶段，这是正常的。
- 编译完成后，会在 `target/` 目录下生成 `biliupforjava.exe`。

---

## 4. 运行与配置

- 将 `target/biliupforjava.exe` 复制到任意你想运行的目录。
- **首次运行**：双击运行，如果没有传递任何参数，控制台会提示并在 `8080` 端口开启一个网页配置向导（Setup Wizard）。
- 浏览器访问 `http://localhost:8080/setup.html`，填写配置后点击保存。
- 程序会在 `exe` 同级目录下生成 `application.yml` 文件并自动退出。
- 再次双击运行，程序将读取 `application.yml` 的配置并正式启动录制和上传服务。