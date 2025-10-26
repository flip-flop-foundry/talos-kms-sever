package dev.flipflopfoundy.taloskms


import ch.qos.logback.classic.LoggerContext
import dev.flipflopfoundy.taloskms.beans.KmsConfigBean
import dev.flipflopfoundy.taloskms.beans.NodeBean
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

import java.security.Security

class KMSServer {
    private static final Logger log = (Logger) LoggerFactory.getLogger(KMSServer.name)
    private Server server


    void start(KmsConfigBean kmsConfig) {

        log.setLevel(kmsConfig.kmsLogLevel)
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("ROOT").setLevel(kmsConfig.rootLogLevel)
        ((Logger)LoggerFactory.getLogger(KMSServiceImpl)).setLevel(kmsConfig.kmsLogLevel)
        ((Logger)LoggerFactory.getLogger(NodeBean)).setLevel(kmsConfig.kmsLogLevel)

        SslContext sslContext
        try {
            sslContext = GrpcSslContexts.configure(
                    SslContextBuilder.forServer(kmsConfig.serverCertFile, kmsConfig.serverKeyFile, kmsConfig.serverKeyPassword)
                            .protocols("TLSv1.3")
            ).build()
        } catch (IllegalArgumentException ex) {
            if (ex.message.contains("File does not contain valid private key")) {
                log.error("Failed to load private key. Is the password correct, or have you not provided a pkcs8 formatted key?")
            }
            throw ex
        }


        KMSServiceImpl kmsService = new KMSServiceImpl()
        kmsService.kmsConfig = kmsConfig

        server = NettyServerBuilder.forAddress(new InetSocketAddress(kmsConfig.bindAddress, kmsConfig.port))
                .addService(kmsService)
                .sslContext(sslContext)
                .intercept(new RemoteAddressInterceptor())
                .build()
                .start()
        log.info("KMS Server started, listening on " + kmsConfig.bindAddress + ":" + kmsConfig.port)
        Runtime.runtime.addShutdownHook(new Thread({
            System.err.println("Shutting down KMS server...")
            this.stop()
            System.err.println("Server shut down.")
        }))
    }

    void stop() {
        if (server != null) {
            server.shutdown()
        }
    }

    void blockUntilShutdown() {
        if (server != null) {
            server.awaitTermination()
        }
    }



    static void main(String[] args) {
        try {
            if (args.length != 2) {
                System.err.println("Usage: KMSServer --config <config-file>")
                System.exit(1)
            }
            Security.addProvider(new BouncyCastleProvider())
            KMSServer server = new KMSServer()
            File configFile = new File(args[1]).absoluteFile
            if (!configFile.exists()) {
                System.err.println("Config file not found: " + configFile.absolutePath)
                System.err.println("Writing default config file to: " + configFile.absolutePath)
                configFile = KmsConfigBean.writeDefaultConfigFile(configFile)
                System.err.println("Please edit the config file and restart the server: " + configFile.absolutePath)
                System.exit(1)
            }
            KmsConfigBean config
            try {
                config = KmsConfigBean.fromFile(configFile)
            } catch (Exception e) {
                log.error("Failed to load config file: ${e.message}", e)
                System.err.println("Failed to load config file: " + e.getMessage())
                System.exit(2)
                return
            }
            System.out.println("Using config file: " + config.configFile.absolutePath)
            try {
                server.start(config)
                server.blockUntilShutdown()
            } catch (Exception e) {
                log.error("Server failed to start or crashed: ${e.message}", e)
                System.err.println("Server failed to start or crashed: " + e.getMessage())
                System.exit(3)
            }
        } catch (Exception e) {
            log.error("Fatal error in main: ${e.message}", e)
            System.err.println("Fatal error: " + e.getMessage())
            System.exit(100)
        }
    }
}
