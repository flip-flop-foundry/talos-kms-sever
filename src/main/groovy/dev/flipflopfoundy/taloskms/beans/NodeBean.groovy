package dev.flipflopfoundy.taloskms.beans

import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock


class NodeBean {

    String nodeUuid
    String ipAddress
    String cipherTextB64
    String nonceB64

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "UTC")
    Instant lastAccess
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "UTC")
    Instant createdAt

    @JsonIgnore
    static Logger log = (Logger) LoggerFactory.getLogger(NodeBean.name)

    @JsonIgnore
    static ObjectMapper mapper = new ObjectMapper()

    @JsonIgnore
    static final ReentrantReadWriteLock nodeDbLock = new ReentrantReadWriteLock()

    static {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }


    @Override
    boolean equals(def o) {

        if (!(o instanceof NodeBean)) {
            return false
        }

        NodeBean nodeBean = (NodeBean) o

        if (nodeUuid != nodeBean.nodeUuid) {
            return false
        }
        if (ipAddress != nodeBean.ipAddress) {
            return false
        }
        if (cipherTextB64 != nodeBean.cipherTextB64) {
            return false
        }
        return nonceB64 == nodeBean.nonceB64
    }

    private static ArrayList<NodeBean> getNodeBeans(KmsConfigBean kmsConfig) {
        nodeDbLock.readLock().lock()
        try {
            ArrayList<NodeBean> nodeBeans = new ArrayList<>()
            if (kmsConfig.nodeDbFile.exists() && kmsConfig.nodeDbFile.length() > 0) {
                try {
                    nodeBeans = mapper.readValue(kmsConfig.nodeDbFile, new TypeReference<ArrayList<NodeBean>>() {})
                } catch (Exception e) {
                    log.error("Failed to read node DB file (${kmsConfig.nodeDbFile.absolutePath}): ${e.message}", e)
                    throw e
                }
            }
            return nodeBeans
        } finally {
            nodeDbLock.readLock().unlock()
        }
    }

    static NodeBean getNodeBeanByNonce(KmsConfigBean kmsConfig, String nonceB64) {
        ArrayList<NodeBean> nodeBeans = getNodeBeans(kmsConfig)
        ArrayList<NodeBean> matchingNodeBeans = nodeBeans.findAll { it.nonceB64 == nonceB64 }
        if (matchingNodeBeans.size() == 1) {

            nodeBeans.find {it.nonceB64 == nonceB64}.setLastAccess(Instant.now())

            writeNodeBeans(kmsConfig, nodeBeans) // Update lastAccess
            return getNodeBeans(kmsConfig).find { it.nonceB64 == nonceB64 }
        } else if (matchingNodeBeans.size() > 1) {
            log.warn("Multiple node beans found with the same nonceB64: ${nonceB64}. This should not happen.")
            throw new InputMismatchException("Multiple node beans found with the same nonceB64: ${nonceB64}")
        } else {
            return null
        }
    }


    /*
    static void rotateNodeDbFiles(KmsConfigBean kmsConfig) {
        nodeDbLock.writeLock().lock()
                File oldestFile = new File(kmsConfig.nodeDbFile.parentFile, "${kmsConfig.nodeDbFile.name}_${kmsConfig.nodeDbFilesToKeep}")
            try {
                File oldestFile = new File(kmsConfig.nodeDbFile.parentFile, "${kmsConfig.nodeDbFile.name}_old")
                if (oldestFile.exists()) {
                    log.info("Deleting old node DB file: ${oldestFile.name}")
                for (int index = kmsConfig.nodeDbFilesToKeep - 1; index >= 0; index--) {
                    File oldFile = new File(kmsConfig.nodeDbFile.parentFile, "${kmsConfig.nodeDbFile.name}_${index}")
                    if (oldFile.exists()) {
                        File newFile = new File(kmsConfig.nodeDbFile.parentFile, "${kmsConfig.nodeDbFile.name}_${index + 1}")
                        oldFile.renameTo(newFile)
                        log.info("Rotated node DB file: ${oldFile.name} -> ${newFile.name}")
                    }
                }
                    oldestFile.delete()
                }
                if (kmsConfig.nodeDbFile.exists()) {
                    File newFile = new File(kmsConfig.nodeDbFile.parentFile, "${kmsConfig.nodeDbFile.name}_1")
                    kmsConfig.nodeDbFile.renameTo(newFile)
                    log.info("Rotated current node DB file: ${kmsConfig.nodeDbFile.name} -> ${newFile.name}")
                }
            } catch (Exception e) {
                log.error("Failed to rotate node DB files: ${e.message}", e)
            }
        } finally {
            nodeDbLock.writeLock().unlock()
        }
    }
    */

    private static File writeNodeBeans(KmsConfigBean kmsConfig, ArrayList<NodeBean> nodeBeans) {
        nodeDbLock.writeLock().lock()
        try {
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(kmsConfig.nodeDbFile, nodeBeans)
            } catch (Exception e) {
                log.error("Failed to write node DB file: ${e.message}", e)
                throw e
            }
            return kmsConfig.nodeDbFile
        } finally {
            nodeDbLock.writeLock().unlock()
        }
    }

    static NodeBean createNodeBean(KmsConfigBean kmsConfig, NodeBean nodeBean) {
        if (!nodeBean.createdAt) {
            nodeBean.createdAt = Instant.now()
        }
        if (!nodeBean.lastAccess) {
            nodeBean.lastAccess = Instant.now()
        }
        ArrayList<NodeBean> nodeBeans = getNodeBeans(kmsConfig)
        nodeBeans.add(nodeBean)
        try {
            writeNodeBeans(kmsConfig, nodeBeans)
        } catch (Exception e) {
            log.error("Failed to create node bean: ${e.message}", e)
            return null
        }
        return getNodeBeanByNonce(kmsConfig, nodeBean.nonceB64)
    }
}
