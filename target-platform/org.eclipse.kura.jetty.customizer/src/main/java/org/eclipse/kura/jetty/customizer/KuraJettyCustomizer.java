/*******************************************************************************
 * Copyright (c) 2018, 2021 Red Hat Inc and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Red Hat Inc
 *  Eurotech
 *******************************************************************************/
package org.eclipse.kura.jetty.customizer;

import java.net.URI;
import java.security.KeyStore;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.X509CertSelector;
import java.util.Collection;
import java.util.Dictionary;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.SessionCookieConfig;

import org.eclipse.equinox.http.jetty.JettyConstants;
import org.eclipse.equinox.http.jetty.JettyCustomizer;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConfiguration.Customizer;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import net.bull.javamelody.Parameter;

public class KuraJettyCustomizer extends JettyCustomizer {

	
	public Server serverWithMelody(Server server) {
		final Map<Parameter, String> parameters = new HashMap<>();
		// to add basic auth:
		// parameters.put(Parameter.AUTHORIZED_USERS, "admin:pwd");

		// to change the default storage directory:
		// parameters.put(Parameter.STORAGE_DIRECTORY, "/tmp/javamelody");

		// to change the default resolution in seconds:
		// parameters.put(Parameter.RESOLUTION_SECONDS, "60");

		// to hide all statistics such as http and sql, except logs:
		// parameters.put(Parameter.DISPLAYED_COUNTERS, "log");
		// parameters.put(Parameter.NO_DATABASE, "true");

		// enable hotspots sampling with a period of 1 second:
		parameters.put(Parameter.SAMPLING_SECONDS, "1.0");

		// set the path of the reports:
		parameters.put(Parameter.MONITORING_PATH, "/");	
	
		final ContextHandlerCollection contexts = new ContextHandlerCollection();
		final ServletContextHandler context = new ServletContextHandler(contexts, "/",
				ServletContextHandler.SESSIONS);

		final net.bull.javamelody.MonitoringFilter monitoringFilter = new net.bull.javamelody.MonitoringFilter();
		monitoringFilter.setApplicationType("Standalone");
		final FilterHolder filterHolder = new FilterHolder(monitoringFilter);
		if (parameters != null) {
			for (final Map.Entry<Parameter, String> entry : parameters.entrySet()) {
				final net.bull.javamelody.Parameter parameter = entry.getKey();
				final String value = entry.getValue();
				filterHolder.setInitParameter(parameter.getCode(), value);
			}
		}
		context.addFilter(filterHolder, "/*",
				EnumSet.of(DispatcherType.INCLUDE, DispatcherType.REQUEST));

		final RequestLogHandler requestLogHandler = new RequestLogHandler();
		contexts.addHandler(requestLogHandler);

		final HandlerCollection handlers = new HandlerCollection();
		handlers.setHandlers(new Handler[] { contexts });
		server.setHandler(handlers);

		return server;
	}
	
	
    @Override
    public Object customizeContext(Object context, Dictionary<String, ?> settings) {
        if (!(context instanceof ServletContextHandler)) {
            return context;
        }

        final ServletContextHandler servletContextHandler = (ServletContextHandler) context;

        final GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setCompressionLevel(9);

        servletContextHandler.setGzipHandler(gzipHandler);

        servletContextHandler.setErrorHandler(new KuraErrorHandler());

        final SessionCookieConfig cookieConfig = servletContextHandler.getSessionHandler().getSessionCookieConfig();

        cookieConfig.setHttpOnly(true);

        return context;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object customizeHttpConnector(final Object connector, final Dictionary<String, ?> settings) {
        if (!(connector instanceof ServerConnector)) {
            return connector;
        }

        final ServerConnector serverConnector = (ServerConnector) connector;
        serverWithMelody(serverConnector.getServer());

        final Set<Integer> ports = (Set<Integer>) settings.get("org.eclipse.kura.http.ports");

        if (ports == null) {
            return null;
        }

        for (final int port : ports) {
            final ServerConnector newConnector = new ServerConnector(serverConnector.getServer(),
                    new HttpConnectionFactory(new HttpConfiguration()));
            customizeConnector(newConnector, port);
            serverConnector.getServer().addConnector(newConnector);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object customizeHttpsConnector(final Object connector, final Dictionary<String, ?> settings) {
        if (!(connector instanceof ServerConnector)) {
            return connector;
        }

        final ServerConnector serverConnector = (ServerConnector) connector;
        serverWithMelody(serverConnector.getServer());

        final Set<Integer> httpsPorts = (Set<Integer>) settings.get("org.eclipse.kura.https.ports");
        final Set<Integer> httpsClientAuthPorts = (Set<Integer>) settings
                .get("org.eclipse.kura.https.client.auth.ports");

        if (httpsPorts != null) {
            for (final int httpsPort : httpsPorts) {
                final Optional<ServerConnector> newConnector = createSslConnector(serverConnector.getServer(), settings,
                        httpsPort, false);
                newConnector.ifPresent(c -> serverConnector.getServer().addConnector(c));
            }
        }

        if (httpsClientAuthPorts != null) {
            for (final int clientAuthPort : httpsClientAuthPorts) {
                final Optional<ServerConnector> newConnector = createSslConnector(serverConnector.getServer(), settings,
                        clientAuthPort, true);
                newConnector.ifPresent(c -> serverConnector.getServer().addConnector(c));
            }
        }

        return null;
    }

    private Optional<ServerConnector> createSslConnector(final Server server, final Dictionary<String, ?> settings,
            final int port, final boolean enableClientAuth) {

        final Optional<String> keyStorePath = getOptional(settings, JettyConstants.SSL_KEYSTORE, String.class);
        final Optional<String> keyStorePassword = getOptional(settings, JettyConstants.SSL_PASSWORD, String.class);

        if (!(keyStorePath.isPresent() && keyStorePassword.isPresent())) {
            return Optional.empty();
        }

        final SslContextFactory.Server sslContextFactory;

        if (enableClientAuth) {
            sslContextFactory = new ClientAuthSslContextFactoryImpl(settings);
        } else {
            sslContextFactory = new SslContextFactory.Server();
        }

        sslContextFactory.setKeyStorePath(keyStorePath.get());
        sslContextFactory.setKeyStorePassword(keyStorePassword.get());
        sslContextFactory.setKeyStoreType("JKS");
        sslContextFactory.setProtocol("TLS");
        sslContextFactory.setTrustManagerFactoryAlgorithm("PKIX");

        sslContextFactory.setWantClientAuth(enableClientAuth);
        sslContextFactory.setNeedClientAuth(enableClientAuth);

        final HttpConfiguration httpsConfig = new HttpConfiguration();
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        final ServerConnector connector = new ServerConnector(server,
                new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(httpsConfig));
        connector.setPort(port);

        return Optional.of(connector);
    }

    private void customizeConnector(final ServerConnector serverConnector, final int port) {
        serverConnector.setPort(port);
        addCustomizer(serverConnector, new ForwardedRequestCustomizer());
    }

    private void addCustomizer(final ServerConnector connector, final Customizer customizer) {
        for (final ConnectionFactory factory : connector.getConnectionFactories()) {
            if (!(factory instanceof HttpConnectionFactory)) {
                continue;
            }

            final HttpConnectionFactory httpConnectionFactory = (HttpConnectionFactory) factory;

            httpConnectionFactory.getHttpConfiguration().setSendServerVersion(false);

            List<Customizer> customizers = httpConnectionFactory.getHttpConfiguration().getCustomizers();
            if (customizers == null) {
                customizers = new LinkedList<>();
                httpConnectionFactory.getHttpConfiguration().setCustomizers(customizers);
            }

            customizers.add(customizer);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getOrDefault(final Dictionary<String, ?> properties, final String key, final T defaultValue) {
        final Object raw = properties.get(key);

        if (defaultValue.getClass().isInstance(raw)) {
            return (T) raw;
        }

        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> getOptional(final Dictionary<String, ?> properties, final String key,
            final Class<T> classz) {
        final Object raw = properties.get(key);

        if (classz.isInstance(raw)) {
            return Optional.of((T) raw);
        }

        return Optional.empty();
    }

    private static final class ClientAuthSslContextFactoryImpl extends SslContextFactory.Server {

        private final Dictionary<String, ?> settings;

        private ClientAuthSslContextFactoryImpl(Dictionary<String, ?> settings) {
            this.settings = settings;

            final boolean isRevocationEnabled = getOrDefault(settings, "org.eclipse.kura.revocation.check.enabled",
                    true);

            setEnableOCSP(isRevocationEnabled);
            setValidatePeerCerts(isRevocationEnabled);

            if (isRevocationEnabled) {
                getOptional(settings, "org.eclipse.kura.revocation.ocsp.uri", String.class)
                        .ifPresent(this::setOcspResponderURL);
                getOptional(settings, "org.eclipse.kura.revocation.crl.path", String.class).ifPresent(this::setCrlPath);
            }
        }

        @Override
        protected PKIXBuilderParameters newPKIXBuilderParameters(KeyStore trustStore,
                Collection<? extends java.security.cert.CRL> crls) throws Exception {
            PKIXBuilderParameters pbParams = new PKIXBuilderParameters(trustStore, new X509CertSelector());

            pbParams.setMaxPathLength(getMaxCertPathLength());
            pbParams.setRevocationEnabled(false);

            if (isEnableOCSP()) {

                final PKIXRevocationChecker revocationChecker = (PKIXRevocationChecker) CertPathValidator
                        .getInstance("PKIX").getRevocationChecker();

                final String responderURL = getOcspResponderURL();
                if (responderURL != null) {
                    revocationChecker.setOcspResponder(new URI(responderURL));
                }
                final Object softFail = getOrDefault(settings, "org.eclipse.kura.revocation.soft.fail", false);
                if (softFail instanceof Boolean && (boolean) softFail) {
                    revocationChecker.setOptions(EnumSet.of(PKIXRevocationChecker.Option.SOFT_FAIL,
                            PKIXRevocationChecker.Option.NO_FALLBACK));
                }

                pbParams.addCertPathChecker(revocationChecker);
            }

            if (getPkixCertPathChecker() != null) {
                pbParams.addCertPathChecker(getPkixCertPathChecker());
            }

            if (crls != null && !crls.isEmpty()) {
                pbParams.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(crls)));
            }

            if (isEnableCRLDP()) {
                // Enable Certificate Revocation List Distribution Points (CRLDP) support
                System.setProperty("com.sun.security.enableCRLDP", "true");
            }

            return pbParams;
        }
    }

}
