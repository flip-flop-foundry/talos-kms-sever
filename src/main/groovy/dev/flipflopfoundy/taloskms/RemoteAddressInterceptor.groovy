package dev.flipflopfoundy.taloskms

import io.grpc.*

/**
 * Used to intercept client calls, extract the remote client address and add it in to the request context for later processing
 */
class RemoteAddressInterceptor implements ServerInterceptor {
    static final Context.Key<InetSocketAddress> REMOTE_ADDRESS_CTX_KEY = Context.key("remote-address")

    @Override
    <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        InetSocketAddress remoteAddr = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR) as InetSocketAddress


        if (remoteAddr != null) {
            Context ctx = Context.current().withValue(REMOTE_ADDRESS_CTX_KEY, remoteAddr)
            return Contexts.interceptCall(ctx, call, headers, next)
        } else {
            return next.startCall(call, headers)
        }
    }
}

