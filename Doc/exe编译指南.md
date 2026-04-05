# biliupforjava 编译为 Windows 可执行文件 (.exe) 指南

本项目基于 Spring Boot 3 与 GraalVM Native Image 技术，支持将 Java 应用程序打包为独立的 Windows `.exe` 可执行文件。该方案可显著提升启动速度并降低运行内存占用，且部署时无需安装 Java 运行环境。

***

## 1. 编译环境配置

在 Windows 环境下进行 AOT (Ahead-of-Time) 编译，需预先安装 C++ 编译工具链与 GraalVM。

### 1.1 安装 Visual Studio Build Tools

GraalVM 编译过程依赖 MSVC 编译器。

1. 下载并运行 [Visual Studio Installer](https://visualstudio.microsoft.com/zh-hans/downloads/)。
2. 勾选 **使用 C++ 的桌面开发** 工作负载。
3. 确保在右侧“安装详细信息”中已勾选以下核心组件：
   - MSVC v143 - VS 2022 C++ x64/x86 生成工具
   - Windows 11 SDK (或 Windows 10 SDK)

### 1.2 配置 GraalVM JDK

1. 下载集成 GraalVM 的 JDK 17 (如 Oracle GraalVM 或 CE 版)。
2. 解压后配置系统环境变量：
   - 新建 `JAVA_HOME`，指向解压目录（如 `C:\graalvm-jdk-17`）。
   - 在 `Path` 变量中添加 `%JAVA_HOME%\bin`。
3. **安装 native-image 组件**：
   - 以管理员权限运行系统自带终端 (CMD / PowerShell)。
   - 执行组件安装命令：
     ```cmd
     gu install native-image
     ```

***

## 2. 注入图标与版本信息资源 (可选)

通过 Windows 资源编译器 (`rc.exe`)，可将自定义图标及版本信息注入最终生成的执行文件中。

### 2.1 准备资源文件

确保项目根目录存在以下文件：

- `icon.ico`: 应用程序图标文件。
- `app.rc`: 资源描述文件，参考内容如下：

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
            VALUE "FileDescription", "biliupforjava1.4.1-beta7.1"
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

### 2.2 编译资源文件

必须先将 `.rc` 编译为 `.res`，方可在 Maven 构建中被链接器读取。

1. 在开始菜单搜索并打开 **x64 Native Tools Command Prompt for VS 2022**。
2. 切换至项目根目录，执行资源编译：
   ```cmd
   rc /nologo /fo app.res app.rc
   ```

> 执行成功后，根目录将生成 `app.res` 文件。

***

## 3. 使用 Tracing Agent 收集反射配置

GraalVM 在编译期间会移除未直接调用的类与方法（Dead Code Elimination）。当项目中存在反射调用（如 Fastjson 反序列化、WebSocket 动态注册）时，直接编译会导致运行时出现 `JSONException: default constructor not found` 或空指针异常。

**在引入新实体类、DTO 或依赖注入变更后，必须通过 Tracing Agent 重新收集并生成反射配置白名单。**

### 配置收集流程：

1. 编译标准 War 包：
   ```cmd
   mvn clean package -DskipTests
   ```
2. 挂载 Agent 启动程序：
   ```cmd
   java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image -jar target/biliLiveRecord-0.0.1-SNAPSHOT.War
   ```
   *(注：挂载 Agent 期间性能下降属正常现象)*
3. **功能覆盖测试**：在程序运行期间，手动触发新增或修改过的业务流程（如：扫码登录、新增录制房间、模拟一次文件结束和分P上传等）。
4. 测试完成后，正常退出程序 (Ctrl+C)。
5. 此时 `src/main/resources/META-INF/native-image` 目录已更新相关 JSON 配置文件，**需将变更提交至 Git 版本库**。后续 AOT 编译将自动读取这些配置。

***

## 4. 执行 EXE 编译

**注意事项：**
Native Image 编译过程资源消耗极大（内存 8GB+，CPU 高负载），且对执行环境要求严格。**必须在独立的系统原生终端（x64 Native Tools Command Prompt for VS 2022）中执行，禁止使用 IDE 内部终端以防沙盒限制导致异常中断。**

1. 打开 **x64 Native Tools Command Prompt for VS 2022**，进入项目根目录。
2. 确保 `pom.xml` 的 `<packaging>` 为 `war`。
3. 运行编译命令：
   ```cmd
   mvn clean -Pnative native:compile -DskipTests
   ```

### 编译说明：

- 耗时通常在 3 至 10 分钟不等。
- 编译期间输出大量 `reachability metadata repository` 与 `[native-image]` 构建日志为正常流程。
- 编译完成后，产物输出在 `target/biliupforjava.exe`。

***

## 5. 首次运行与初始化配置

1. 将 `target/biliupforjava.exe` 移动至目标部署目录。
2. **首次启动**：直接运行程序。若未检测到启动参数或配置文件，控制台将提示于 `8080` 端口启动 Setup Wizard。
3. 浏览器访问 `http://localhost:8080/setup.html`，完成参数配置并保存。
4. 程序将在同级目录生成 `application.yml` 后自动退出。
5. 再次运行程序，服务将加载配置文件并正式进入工作状态。

