package bayou.ssl;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * SSL configuration.
 * <p>
 *     This class is basically a builder for {@link javax.net.ssl.SSLContext}
 *     from key-store and/or trust-store files.
 *     For example:
 * </p>
 * <pre>
 *     SSLContext context = new SslConf()
 *         .keyStoreFile("my-certs.jks)
 *         .keyStorePass("password")
 *         .keyManagerFactoryAlgorithm("PKIX")
 *         .createContext();
 * </pre>
 */
public class SslConf
{
    /**
     * Create an SslConf with default values.
     */
    public SslConf()
    {

    }


    String keyStoreFile=null;
    /**
     * The key store file path.
     * <p><code>
     *     default: null (none)
     * </code></p>
     * <p>
     *     This file contains private keys used for local certificates.
     * </p>
     * <p>
     *     For server, this field usually should be non-null.
     * </p>
     * <p>
     *     For client, this field usually is null, unless client certificate is required.
     * </p>
     * @return `this`
     */
    public SslConf keyStoreFile(String keyStoreFile)
    {
        this.keyStoreFile = keyStoreFile;
        return this;
    }


    String keyStorePass =null;
    /**
     * The key store file password.
     * <p><code>
     *     default: null
     * </code></p>
     * <p>
     *     This field must be non-null if `keyStoreFile` is non-null.
     * </p>
     * <p>
     *     This password is both for the file, and for all private keys in the file.
     * </p>
     * @return `this`
     */
    public SslConf keyStorePass(String keyStorePass)
    {
        this.keyStorePass = keyStorePass;
        return this;
    }

    String keyStoreType = KeyStore.getDefaultType();
    /**
     * The keys tore file type.
     * This value is used for {@link KeyStore#getInstance(String)  KeyStore.getInstance(type)}.
     * <p><code>
     *     default: {@link KeyStore#getDefaultType() KeyStore.getDefaultType()}
     * </code></p>
     * <p>
     *     See <a href="http://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#KeyStore"
     *     >standard values</a>, including "jks", "pkcs12" etc.
     * </p>
     * <p>
     *     On Sun/Oracle JRE, the factory-default value is "jks".
     * </p>
     * @return `this`
     */
//    * <p>
//    *     `KeyStore.getDefaultType()` can be affected by
//    *     `java.security.Security.setProperty("keystore.type", VALUE)`.
//    * </p>
    public SslConf keyStoreType(String keyStoreType)
    {
        this.keyStoreType = keyStoreType;
        return this;
    }


    String keyManagerFactoryAlgorithm = KeyManagerFactory.getDefaultAlgorithm();
    // algorithm -> KeyManager class
    //    SunX509 -> sun.security.ssl.SunX509KeyManagerImpl
    //       PKIX -> sun.security.ssl.X509KeyManagerImpl
    // NewSunX509 -> sun.security.ssl.X509KeyManagerImpl
    /**
     * Algorithm for KeyManagerFactory.
     *     This value is used for
     *     {@link KeyManagerFactory#getInstance(String) KeyManagerFactory.getInstance(algorithm)}
     * <p><code>
     *     default: {@link KeyManagerFactory#getDefaultAlgorithm() KeyManagerFactory.getDefaultAlgorithm()}
     * </code></p>
     * <p>
     *     See <a href=
     *     "http://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#KeyManagerFactory">
     *     standard values</a>, including "PKIX".
     * </p>
     * <p>
     *     On Sun/Oracle JRE, supported values include  "PKIX", "SunX509", "NewSunX509".
     *     The factory-default value is "SunX509",
     *     which is probably fine for most use cases;
     *     However, apparently "SunX509" does not support SNI at this point, and "PKIX" does.
     *     Try "PKIX" if SNI support is required.
     * </p>
     * @return `this`
     */
    //     * <p>
    //     *     `KeyManagerFactory.getDefaultAlgorithm()` can be affected by
    //     *     `java.security.Security.setProperty("ssl.KeyManagerFactory.algorithm", VALUE)`.
    //     * </p>
    public SslConf keyManagerFactoryAlgorithm(String keyManagerFactoryAlgorithm)
    {
        this.keyManagerFactoryAlgorithm = keyManagerFactoryAlgorithm;
        return this;
    }





    String trustStoreFile=null;
    /**
     * The trust store file path.
     * <p><code>
     *     default: null (system default)
     * </code></p>
     * <p>
     *     This file contains root certificates for validating peer certificates.
     * </p>
     * <p>
     *     If null, a system default trust store is used, which is usually
     *     <code>"JAVA-HOME/lib/security/cacerts"</code>.
     *     See details at
     *     <a href=
     *     "http://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#X509TrustManager"
     *     >JSSE Guide</a>, starting at
     *     <i>"Note: If a null KeyStore parameter is passed to ..."</i>
     * </p>
     * <p>
     *     If null, `trustStorePass`, `trustStoreType`, and `trustManagerFactoryAlgorithm`
     *     configurations are irrelevant and ignored.
     * </p>
     * <p>
     *     For server, this field usually can be null,
     *     unless the server needs to validate client certificates with non-standard CAs.
     * </p>
     * <p>
     *     for client, this field usually can be null,
     *     unless the client needs to validate server certificates with non-standard CAs.
     * </p>
     * <p>
     *     Note that the same key store file for the server can be used as
     *     the trust store for the client;
     *     this is convenient for local testing with self-signed certificates.
     * </p>
     * <p>
     *     See also {@link #trustAll()}.
     * </p>
     *
     * @return `this`
     */
    public SslConf trustStoreFile(String trustStoreFile)
    {
        this.trustStoreFile = trustStoreFile;
        return this;
    }


    String trustStorePass =null;
    /**
     * The trust store file password.
     * <p><code>
     *     default: null
     * </code></p>
     * <p>
     *     This password is only used to check the integrity of the trust store file.
     *     It is not required even if `trustStoreFile` is non-null.
     * </p>
     * <p>
     *     Note that the factory-default password for
     *     <code>"JAVA-HOME/lib/security/cacerts"</code>
     *     is "changeit".
     * </p>
     * @return `this`
     */
    public SslConf trustStorePass(String trustStorePass)
    {
        this.trustStorePass = trustStorePass;
        return this;
    }


    String trustStoreType = KeyStore.getDefaultType();
    /**
     * The trust store file type.
     * This value is used for {@link KeyStore#getInstance(String)  KeyStore.getInstance(type)}
     * (note that KeyStore class can also represent trust stores).
     * <p><code>
     *     default: {@link KeyStore#getDefaultType() KeyStore.getDefaultType()}
     * </code></p>
     * <p>
     *     See <a href="http://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#KeyStore"
     *     >standard values</a>, including "jks", "pkcs12" etc.
     * </p>
     * <p>
     *     On Sun/Oracle JRE, the factory-default value is "jks".
     * </p>
     * @return `this`
     */
//    * <p>
//    *     `KeyStore.getDefaultType()` can be affected by
//    *     `java.security.Security.setProperty("keystore.type", VALUE)`.
//    * </p>
    public SslConf trustStoreType(String trustStoreType)
    {
        this.trustStoreType = trustStoreType;
        return this;
    }

    String trustManagerFactoryAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
    /**
     * Algorithm for KeyManagerFactory.
     *     This value is used for
     *     {@link TrustManagerFactory#getInstance(String) TrustManagerFactory.getInstance(algorithm)}
     * <p><code>
     *     default: {@link TrustManagerFactory#getDefaultAlgorithm() TrustManagerFactory.getDefaultAlgorithm()}
     * </code></p>
     * <p>
     *     See <a href=
     *     "http://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#TrustManagerFactory">
     *     standard values</a>, including "PKIX".
     * </p>
     * <p>
     *     On Sun/Oracle JRE, the factory-default value is "PKIX",
     *     which is probably fine for most use cases.
     *     <!-- "SunX509" is supported, and apparently equivalent to "PKIX" -->
     * </p>
     * @return `this`
     */
//     * <p>
//     *     `TrustManagerFactory.getDefaultAlgorithm()` can be affected by
//     *     `java.security.Security.setProperty("ssl.TrustManagerFactory.algorithm", VALUE)`.
//     * </p>
    public SslConf trustManagerFactoryAlgorithm(String trustManagerFactoryAlgorithm)
    {
        this.trustManagerFactoryAlgorithm = trustManagerFactoryAlgorithm;
        return this;
    }


    @SuppressWarnings("RedundantStringConstructorCall")
    static final String TRUST_ALL = new String("@TRUST ALL@"); // sentinel for trustStoreFile
    /**
     * Use a TrustManager that accept all peer certificates, including all self-signed ones.
     * <p>
     *     Some applications may opt to accept all peer certificates during handshake.
     * </p>
     * <p>
     *     `trustAll` and `{@link #trustStoreFile(String) trustStoreFile}` override each other;
     *     whichever specified last is the effective setting.
     * </p>
     * @return `this`
     */
    public SslConf trustAll()
    {
        trustStoreFile = TRUST_ALL; // sentinel
        return this;
    }




    String contextProtocol = "TLS";
    /**
     * The protocol for {@link SSLContext#getInstance(String) SSLContext.getInstance(protocol)}
     * <p><code>
     *     default: "TLS"
     * </code></p>
     * <p>
     *     See <a href=
     *     "http://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#SSLContext"
     *     >standard values</a>.
     * </p>
     * <p>
     *     The default value "TLS" should be fine for most use cases.
     *     <!--
     *      "TLS" is apparently equivalent to "TLSv1.2" (at this time).
     *      it supports older versions as well:
     *          sslContext.getDefaultSSLParameters().getProtocols() =>  SSLv3 TLSv1 TLSv1.1 TLSv1.2
     *     -->
     * </p>
     * @return `this`
     */
    public SslConf contextProtocol(String contextProtocol)
    {
        this.contextProtocol = contextProtocol;
        return null;
    }


    /**
     * Create an SSLContext.
     * <p>
     *     This method depends on field
     *     <br> {@link #contextProtocol(String) contextProtocol}
     *     <br> and methods
     *     <br> {@link #createKeyManagers()}
     *     <br> {@link #createTrustManagers()}
     * </p>
     */
    public SSLContext createContext() throws Exception
    {
        SSLContext sslContext = SSLContext.getInstance(contextProtocol);

        sslContext.init(createKeyManagers(), createTrustManagers(), null);

        return sslContext;
    }


    /**
     * Create key managers.
     * <p>
     *     This method depends on fields
     *     <br> {@link #keyStoreFile(String) keyStoreFile}
     *     <br> {@link #keyStorePass(String) keyStorePass}
     *     <br> {@link #keyStoreType(String) keyStoreType}
     *     <br> {@link #keyManagerFactoryAlgorithm(String) keyManagerFactoryAlgorithm}
     * </p>
     */
    public KeyManager[] createKeyManagers() throws Exception
    {
        if(keyStoreFile==null) // no default key store
        {
            return new KeyManager[0];
        }

        char[] pwd = keyStorePass ==null? null : keyStorePass.toCharArray();

        KeyStore ks = KeyStore.getInstance(keyStoreType);
        try(FileInputStream ksInput = new FileInputStream(keyStoreFile))
        {   ks.load(ksInput, pwd);   }
        // pwd here can be null - it's not used to decrypt the file, it's just to verify file integrity.
        // null means no integrity check. probably not a big deal for app.

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(keyManagerFactoryAlgorithm);
        kmf.init(ks, pwd);
        // pwd here cannot be null - it's used to protect private keys.
        // we use the same keyStorePass for both the file and the private keys inside
        // if necessary, we'll need to add a `keyStoreKeyPass` conf

        return kmf.getKeyManagers();
    }


    /**
     * Create trust managers.
     * <p>
     *     If {@link #trustAll()} is specified, return a TrustManager that trust all certificates.
     * </p>
     * <p>
     *     If {@link #trustStoreFile(String) trustStoreFile}==null,
     *     return system default TrustManagers.
     * </p>
     * <p>
     *     Otherwise, this method depends on fields
     *     <br> {@link #trustStoreFile(String) trustStoreFile}
     *     <br> {@link #trustStorePass(String) trustStorePass}
     *     <br> {@link #trustStoreType(String) trustStoreType}
     *     <br> {@link #trustManagerFactoryAlgorithm(String) trustManagerFactoryAlgorithm}
     * </p>
     */
    public TrustManager[] createTrustManagers() throws Exception
    {
        //noinspection StringEquality
        if(trustStoreFile==TRUST_ALL)
        {
            GullibleTrustManager tm = new GullibleTrustManager();
            return new TrustManager[]{tm};
        }

        if(trustStoreFile==null)
        {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(trustManagerFactoryAlgorithm);
            tmf.init( (KeyStore)null );  // system default trust store
            return tmf.getTrustManagers();
        }

        char[] pwd = trustStorePass ==null? null : trustStorePass.toCharArray();

        KeyStore ks = KeyStore.getInstance(trustStoreType);
        try(FileInputStream ksInput = new FileInputStream(trustStoreFile))
        {   ks.load(ksInput, pwd);   }
        // password here can be null - it's not used to decrypt the file, it's just to verify file integrity.
        // null means no integrity check. probably not a big deal for app.
        // app can usually leave trustStorePass as null. but if it's supplied, we'll use it.

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(trustManagerFactoryAlgorithm);
        tmf.init(ks);
        return tmf.getTrustManagers();
    }




    static class GullibleTrustManager implements X509TrustManager
    {
        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
        {
            // ok
        }
        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
        {
            // ok
        }

        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            return new X509Certificate[0];
            // problem: we accept ALL issuers, but there's no way to express that in this method.
            // it seems fine, so far, to return an empty array.
            // this array is sent from server to client if client cert is requested in handshake
            //    and empty array means all CAs are accepted.
        }
    }

}
