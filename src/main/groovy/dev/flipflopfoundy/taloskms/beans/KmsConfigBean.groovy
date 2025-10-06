package dev.flipflopfoundy.taloskms.beans

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

import java.util.logging.Level

class KmsConfigBean {

    @JsonIgnore
    File configFile

    File nodeDbFile
    File serverCertFile
    File serverKeyFile
    String serverKeyPassword

    Integer port //TCP port to bind the server to
    String bindAddress // Address to bind the server to
    @JsonIgnore
    Level kmsLogLevel
    @JsonProperty("kmsLogLevel")
    String kmsLogLevelName
    @JsonIgnore
    Level rootLogLevel
    @JsonProperty("rootLogLevel")
    String rootLogLevelName

    static ObjectMapper mapper = new ObjectMapper(new YAMLFactory())


    static KmsConfigBean fromFile(File configFile) {

        KmsConfigBean config = mapper.readValue(configFile, KmsConfigBean)
        config.configFile = configFile
        config.kmsLogLevel = Level.parse(config.kmsLogLevelName)
        config.rootLogLevel = Level.parse(config.rootLogLevelName)
        if (!config.bindAddress) config.bindAddress = "0.0.0.0"

        return config
    }

    boolean writeToFile(File configFile) {


        mapper.writeValue(configFile, this)

        return true
    }

    static File writeDefaultConfigFile(File file) {
        KmsConfigBean defaultConfig = new KmsConfigBean()
        defaultConfig.configFile = file
        defaultConfig.nodeDbFile = new File(file.parentFile, "nodeDbFile.json")
        defaultConfig.serverCertFile = new File("server.crt")
        defaultConfig.serverKeyFile = new File("server.key")
        defaultConfig.serverKeyPassword = "changeit"
        defaultConfig.kmsLogLevelName = Level.INFO.name
        defaultConfig.rootLogLevelName = Level.WARNING.name
        defaultConfig.port = 50051
        defaultConfig.bindAddress = "0.0.0.0"

        // Write YAML as usual
        defaultConfig.writeToFile(defaultConfig.configFile)

        // Read YAML content
        String yamlContent = file.text
        // Prepare comments
        String comments = """# Talos KMS Configuration File\n" +
                "# nodeDbFile: Path to the node database json file, it will be created if it doesnt exist.\n" +
                "# serverCertFile: Path to the server certificate file.\n" +
                "# serverKeyFile: Path to the server private key file, needs to be pkcs8 format.\n" +
                "# serverKeyPassword: Password for the server private key.\n" +
                "# port: Port to run the KMS server on.\n" +
                "# bindAddress: Network interface/address to bind the server to (e.g., 0.0.0.0, 127.0.0.1).\n" +
                "# kmsLogLevel: Log level for KMS logs (e.g., INFO, DEBUG).\n" +
                "# rootLogLevel: Log level for root logger (e.g., WARNING, INFO).\n\n"""
        // Write comments + YAML back to file
        file.text = comments + yamlContent

        return defaultConfig.configFile
    }
}
