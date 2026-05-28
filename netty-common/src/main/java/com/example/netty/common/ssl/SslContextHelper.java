package com.example.netty.common.ssl;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;

public class SslContextHelper {

    private static SslContext serverSslContext;
    private static SslContext clientSslContext;
    private static SelfSignedCertificate sharedCert;

    static {
        try {
            // Generate a self-signed certificate for the local domain
            sharedCert = new SelfSignedCertificate("localhost");
        } catch (CertificateException e) {
            throw new RuntimeException("Failed to initialize self-signed certificate", e);
        }
    }

    /**
     * Builds and returns a server-side SSL/TLS context.
     */
    public static SslContext getServerSslContext() throws SSLException {
        if (serverSslContext == null) {
            serverSslContext = SslContextBuilder.forServer(sharedCert.certificate(), sharedCert.privateKey())
                    .build();
        }
        return serverSslContext;
    }

    /**
     * Builds and returns a client-side SSL/TLS context.
     * Trusts the self-signed certificate for testing purposes.
     */
    public static SslContext getClientSslContext() throws SSLException {
        if (clientSslContext == null) {
            clientSslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
        }
        return clientSslContext;
    }
}
