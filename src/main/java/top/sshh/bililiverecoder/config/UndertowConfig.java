package top.sshh.bililiverecoder.config;

import io.undertow.server.DefaultByteBufferPool;
import io.undertow.websockets.jsr.WebSocketDeploymentInfo;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UndertowConfig {

    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> undertowWebSocketCustomizer() {
        return factory -> factory.addDeploymentInfoCustomizers(deploymentInfo -> {
            WebSocketDeploymentInfo webSocketDeploymentInfo = (WebSocketDeploymentInfo) deploymentInfo.getServletContextAttributes().get(WebSocketDeploymentInfo.ATTRIBUTE_NAME);
            if (webSocketDeploymentInfo == null) {
                webSocketDeploymentInfo = new WebSocketDeploymentInfo();
                deploymentInfo.getServletContextAttributes().put(WebSocketDeploymentInfo.ATTRIBUTE_NAME, webSocketDeploymentInfo);
            }
            if (webSocketDeploymentInfo.getBuffers() == null) {
                webSocketDeploymentInfo.setBuffers(new DefaultByteBufferPool(false, 1024));
            }
        });
    }
}
