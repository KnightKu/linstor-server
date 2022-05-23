package com.linbit.linstor.netcom.ssl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.ServiceName;
import com.linbit.linstor.api.interfaces.serializer.CommonSerializer;
import com.linbit.linstor.core.objects.Node;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.modularcrypto.ModularCryptoProvider;
import com.linbit.linstor.netcom.ConnectionObserver;
import com.linbit.linstor.netcom.MessageProcessor;
import com.linbit.linstor.netcom.TcpConnectorService;
import com.linbit.linstor.security.AccessContext;

public class SslTcpConnectorService extends TcpConnectorService
{
    private final ModularCryptoProvider cryptoProvider;
    private final SSLContext sslCtx;

    public SslTcpConnectorService(
        final ErrorReporter errorReporter,
        final CommonSerializer commonSerializer,
        final MessageProcessor msgProcessorRef,
        @Nullable final SocketAddress bindAddress,
        final AccessContext peerAccCtxRef,
        final AccessContext privAccCtxRef,
        final ConnectionObserver connObserverRef,
        final ModularCryptoProvider cryptoProviderRef,
        final String sslProtocol,
        final String keyStoreFile,
        final char[] keyStorePasswd,
        final char[] keyPasswd,
        final String trustStoreFile,
        final char[] trustStorePasswd
    )
        throws IOException, NoSuchAlgorithmException, KeyManagementException,
        UnrecoverableKeyException, KeyStoreException, CertificateException
    {
        super(
            errorReporter,
            commonSerializer,
            msgProcessorRef,
            bindAddress,
            peerAccCtxRef,
            privAccCtxRef,
            connObserverRef
        );
        cryptoProvider = cryptoProviderRef;
        sslCtx = cryptoProviderRef.createSslContext(sslProtocol);
        initialize(keyStoreFile, keyStorePasswd, keyPasswd, trustStoreFile, trustStorePasswd);
    }

    private void initialize(
        final String keyStoreFile,
        final char[] keyStorePasswd,
        final char[] keyPasswd,
        final String trustStoreFile,
        final char[] trustStorePasswd
    )
        throws ImplementationError, NoSuchAlgorithmException, KeyManagementException,
        KeyStoreException, IOException, CertificateException, UnrecoverableKeyException
    {
        try
        {
            serviceInstanceName = new ServiceName("SSL" + serviceInstanceName.displayValue);
        }
        catch (InvalidNameException nameExc)
        {
            throw new ImplementationError(
                String.format(
                    "%s class contains an invalid name constant",
                    TcpConnectorService.class.getName()
                ),
                nameExc
            );
        }

        cryptoProvider.initializeSslContext(
            sslCtx,
            keyStoreFile, keyStorePasswd, keyPasswd,
            trustStoreFile, trustStorePasswd
        );
    }

    @Override
    protected SslTcpConnectorPeer createTcpConnectorPeer(
        final String peerId,
        final SelectionKey connKey,
        final boolean outgoing,
        final Node node
    )
    {
        SslTcpConnectorPeer newPeer;
        try
        {
            InetSocketAddress address = null;
            if (outgoing)
            {
                @SuppressWarnings("resource")
                SocketChannel channel = (SocketChannel) connKey.channel();
                @SuppressWarnings("resource")
                Socket socket = channel.socket();
                String host = socket.getInetAddress().getHostAddress();
                int port = socket.getPort();
                address = new InetSocketAddress(host, port);
            }

            newPeer = new SslTcpConnectorPeer(
                errorReporter,
                commonSerializer,
                peerId,
                this,
                connKey,
                defaultPeerAccCtx,
                sslCtx,
                address,
                node
            );
        }
        catch (SSLException sslExc)
        {
            throw new RuntimeException(sslExc);
        }
        return newPeer;
    }
}
