# Netty 插件化架构运行与开发指南

本指南详细说明了如何使用本系统所采用的插件化底座，进行业务的动态扩展开发、编译打包及部署运行。

---

## 1. 插件设计理念

本系统的 Netty 底座已实现“网络连接”与“业务逻辑”的彻底解耦。
- **通信底座 (`netty-server` / `netty-client`)**：只负责 socket 生命周期的维护、网络异常捕获、SSL 编解码、粘包拆包等。
- **业务插件 (`MessagePlugin`)**：负责具体的业务逻辑（例如心跳处理、业务包处理、流量统计等），解耦各模块间代码。

---

## 2. 插件发现与注册方式

插件系统支持两种接入方式：
1. **静态 Spring 依赖注入**：在主应用中，任何被 `@Component` 标注的 `MessagePlugin` 实现类，在启动时都会被 Spring 自动扫描并组装到 `PluginRegistry`。
2. **动态 JAR 插件装载**：主应用启动时，会扫描同级目录下的 `plugins/` 文件夹。若发现 JAR 包，会使用独立的 `URLClassLoader` 和 Java SPI 机制将声明的 `MessagePlugin` 实现类加载进来。

---

## 3. 开发一个业务插件 (以客户端插件开发为例)

您可以利用工程里的 `netty-plugin-client-demo` 模块作为脚手架直接开发新业务：

### 第一步：创建插件实现类
实现 `com.example.netty.common.plugin.MessagePlugin` 接口：

```java
package com.example.netty.plugin.client;

import com.example.netty.common.plugin.MessagePlugin;
import com.example.netty.common.proto.MessagePacket;
import com.example.netty.common.proto.MessageType;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyCustomClientPlugin implements MessagePlugin {
    private static final Logger log = LoggerFactory.getLogger(MyCustomClientPlugin.class);

    @Override
    public String getId() {
        return "my-custom-client-plugin"; // 插件唯一识别 ID
    }

    @Override
    public void onLoad() {
        log.info("[Custom Plugin] MyCustomClientPlugin Loaded!");
    }

    @Override
    public void onUnload() {
        log.info("[Custom Plugin] MyCustomClientPlugin Unloaded.");
    }

    @Override
    public boolean supports(MessagePacket packet) {
        // 支持拦截 PONG（心跳回包）报文
        return packet.getType() == MessageType.PONG;
    }

    @Override
    public boolean handle(ChannelHandlerContext ctx, MessagePacket packet, String connectionType) {
        log.info("[Custom Plugin][{}][PONG] Intercepted heartbeats! PingTS={}", 
                connectionType, packet.getPong().getTimestamp());
        
        // 返回 false 表示继续向后传播给其他心跳插件处理
        // 返回 true 则截断包传播，终止后续插件执行
        return false; 
    }
    
    @Override
    public int getOrder() {
        return 10; // 执行顺序，值越小越优先执行
    }
}
```

### 第二步：配置 SPI 服务端声明
在 `src/main/resources/META-INF/services/com.example.netty.common.plugin.MessagePlugin` 文件中加入新类的全路径：
```text
com.example.netty.plugin.client.MyCustomClientPlugin
```

---

## 4. 编译与自动化部署

本系统各脚手架模块的 `build.gradle` 均集成了拷贝任务，支持自动部署。

1. **执行编译打包命令**：
   在项目根目录下执行：
   ```bash
   ./gradlew :netty-plugin-client-demo:build
   ```

2. **自动拷贝输出**：
   打包生成的 JAR 文件会自动部署到 `netty-client` 的运行子目录中：
   - 客户端插件 JAR -> `netty-client/plugins/`
   - 服务端插件 JAR -> `netty-server/plugins/`

---

## 5. 系统运行与验证

在根目录下分别打开两个终端，分别启动服务端和客户端：

### 5.1 启动服务端
```bash
./gradlew :netty-server:bootRun
```
控制台启动日志输出中，会显示自动扫描并装载了服务端的静态插件与动态插件：
```text
c.example.netty.server.core.NettyServer  : Registering Spring-managed Netty plugins...
c.e.netty.common.plugin.PluginRegistry   : Registering plugin: [business-request-server] (com.example.netty.server.plugin.BusinessRequestServerPlugin)
c.e.netty.common.plugin.PluginRegistry   : Registering plugin: [ping-pong-server] (com.example.netty.server.plugin.PingPongServerPlugin)
c.example.netty.server.core.NettyServer  : Loading dynamic plugins from 'plugins' directory...
c.e.n.common.plugin.DynamicPluginLoader  : Found plugin jar: netty-plugin-server-demo-1.0.0.jar
c.e.netty.common.plugin.PluginRegistry   : Registering plugin: [demo-spi-server-plugin] (com.example.netty.plugin.server.DemoServerPlugin)
```

### 5.2 启动客户端
```bash
./gradlew :netty-client:bootRun
```
控制台启动日志输出中，会显示扫描并加载了客户端的动态插件：
```text
c.example.netty.client.core.NettyClient  : Loading dynamic client plugins from 'plugins' directory...
c.e.n.common.plugin.DynamicPluginLoader  : Found plugin jar: netty-plugin-client-demo-1.0.0.jar
c.e.netty.common.plugin.PluginRegistry   : Registering plugin: [demo-spi-client-plugin] (com.example.netty.plugin.client.DemoClientPlugin)
```

### 5.3 联调验证
1. **普通业务测试**：客户端自动向服务端发送 `REQUEST (seq 1001)`，由服务端的业务插件进行回复，客户端响应插件解析成功。
2. **SPI 服务端插件拦截测试**：客户端随后向服务端投递 `SPIDemoCommand` 自定义请求，服务端由 `demo-spi-server-plugin` 成功解析和吞掉该包，并且客户端控制台成功打印 `Response from DemoServerPlugin via TCP`，这表明 SPI 通道工作完全正常。
