package dev.flipflopfoundy.taloskms

import dev.flipflopfoundy.taloskms.beans.KmsConfigBean
import dev.flipflopfoundy.taloskms.beans.NodeBean
import io.grpc.Status
import io.grpc.stub.StreamObserver
import com.google.protobuf.ByteString

import io.grpc.Context

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

class KMSServiceImpl extends KMSServiceGrpc.KMSServiceImplBase {
    private static final Logger log = (Logger) LoggerFactory.getLogger(KMSServiceImpl.class)

    KmsConfigBean kmsConfig

    static final int AES_KEY_SIZE = 32 // 256 bits
    static final int NONCE_SIZE = 12 // 96 bits, standard for GCM
    static final int TAG_BIT_LENGTH = 128 // GCM auth tag size in bits

    @Override
    void seal(Request request, StreamObserver<Response> responseObserver) {
        try {
            InetSocketAddress socketAddress = RemoteAddressInterceptor.REMOTE_ADDRESS_CTX_KEY.get(Context.current())
            NodeBean newNodeBean = new NodeBean()
            newNodeBean.ipAddress = socketAddress.getAddress().toString().replaceFirst("/", "")
            newNodeBean.nodeUuid = request.getNodeUuid()
            log.info("Seal request received from ${newNodeBean.ipAddress}")

            byte[] newNodeKey = new byte[AES_KEY_SIZE]
            new SecureRandom().nextBytes(newNodeKey)
            byte[] nonce = new byte[NONCE_SIZE]
            new SecureRandom().nextBytes(nonce)
            Cipher cipher = Cipher.getInstance('AES/GCM/NoPadding', 'BC')
            SecretKeySpec keySpec = new SecretKeySpec(newNodeKey, 'AES')
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BIT_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            cipher.updateAAD(newNodeBean.nodeUuid.getBytes("UTF-8"))
            cipher.updateAAD(newNodeBean.ipAddress.getBytes("UTF-8"))
            byte[] ciphertext = cipher.doFinal(request.getData().toByteArray())
            newNodeBean.cipherTextB64 = Base64.getEncoder().encodeToString(ciphertext)
            newNodeBean.nonceB64 = Base64.getEncoder().encodeToString(nonce)
            newNodeBean = NodeBean.createNodeBean(kmsConfig, newNodeBean)
            if (!newNodeBean) {
                String msg = "Failed to save new node for UUID ${newNodeBean.nodeUuid}"
                log.error(msg)
                responseObserver.onError(Status.INTERNAL.withDescription(msg).asException())
                return
            }
            byte[] nonceAndKey = new byte[NONCE_SIZE + AES_KEY_SIZE]
            System.arraycopy(nonce, 0, nonceAndKey, 0, nonce.length)
            System.arraycopy(newNodeKey, 0, nonceAndKey, nonce.length, newNodeKey.length)
            ByteString newNodeKeyByteString = ByteString.copyFrom(nonceAndKey)
            Response response = Response.newBuilder().setData(newNodeKeyByteString).build()


            try {
                responseObserver.onNext(response)
                responseObserver.onCompleted()
                log.debug("\tCreated and responded to new node: ${newNodeBean.nonceB64}")
            } catch (Exception e1) {
                log.error("Failed to send response to client for nonce ${newNodeBean?.nonceB64}: ${e1.message}", e1)
                throw e1
            }
        } catch (Exception e) {
            log.error("Error in seal: ${e.message}", e)
            responseObserver.onError(Status.INTERNAL.withDescription("Seal failed").asException())
        }
    }

    @Override
    void unseal(Request request, StreamObserver<Response> responseObserver) {

        Response response
        byte[] decryptedData = new byte[32]
        try {
            byte[] connectingNonce = Arrays.copyOfRange(request.getData().toByteArray(), 0, NONCE_SIZE)
            String connectingNonceB64 = Base64.encoder.encodeToString(connectingNonce)
            byte[] connectingKey = Arrays.copyOfRange(request.getData().toByteArray(), NONCE_SIZE, request.getData().toByteArray().length)
            String connectingIp = RemoteAddressInterceptor.REMOTE_ADDRESS_CTX_KEY.get(Context.current()).getAddress().toString().replaceFirst("/", "")
            String connectingNodeUuid = request.getNodeUuid()
            log.info("Unseal request received from ${connectingIp} for node Nonce ${connectingNonceB64}")
            NodeBean nodeBean = NodeBean.getNodeBeanByNonce(kmsConfig, connectingNonceB64)
            if (nodeBean == null) {
                String msg = "No node found for nonce ${connectingNonceB64}"
                log.warn(msg)
                responseObserver.onError(Status.NOT_FOUND.withDescription(msg).asException())
                return
            }
            log.info("\tFound matching node for nonce ${connectingNonceB64}")
            Cipher cipher = Cipher.getInstance('AES/GCM/NoPadding', 'BC')
            SecretKeySpec keySpec = new SecretKeySpec(connectingKey, 'AES')
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BIT_LENGTH, Base64.decoder.decode(nodeBean.nonceB64))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.updateAAD(connectingNodeUuid.getBytes("UTF-8"))
            cipher.updateAAD(connectingIp.getBytes("UTF-8"))


            try {
                decryptedData = cipher.doFinal(Base64.decoder.decode(nodeBean.cipherTextB64))
            } catch (AEADBadTagException e) {
                log.error("Decryption failed (bad tag) for node UUID ${connectingNodeUuid} from IP ${connectingIp}, has IP changed?", e)
                log.error(e.stackTrace.toString())
            } catch (Exception e) {
                log.error("Decryption failed for node UUID ${connectingNodeUuid} from IP ${connectingIp}: ${e.message}", e)
                log.error(e.stackTrace.toString())

            }

            if (!decryptedData) {
                log.error("Failed to decrypt data for nonce ${connectingNonceB64} - returning random data")
                new SecureRandom().nextBytes(decryptedData)
                response = Response.newBuilder().setData(ByteString.copyFrom(decryptedData)).build()
            } else if (decryptedData.length != 32) {
                log.warn("Decrypted data length mismatch: expected 32, got ${decryptedData.length} - returning random data")
                new SecureRandom().nextBytes(decryptedData)
                response = Response.newBuilder().setData(ByteString.copyFrom(decryptedData)).build()
                return
            } else {
                log.info("\tUnseal successful for nonce ${connectingNonceB64}")
                response = Response.newBuilder().setData(ByteString.copyFrom(decryptedData)).build()
            }


            try {
                responseObserver.onNext(response)
                responseObserver.onCompleted()
                log.info("\tResponse sent sucesffuly to client for nonce ${connectingNonceB64}")
            } catch (Exception e1) {
                log.error("Failed to send response to client for nonce ${connectingNonceB64}: ${e1.message}", e1)
                throw e1
            }

        } catch (Exception e) {
            log.error("Error in unseal: ${e.message} - returning random data", e)

            new SecureRandom().nextBytes(decryptedData)
            response = Response.newBuilder().setData(ByteString.copyFrom(decryptedData)).build()
            responseObserver.onNext(response)
            responseObserver.onCompleted()
        }
    }
}
