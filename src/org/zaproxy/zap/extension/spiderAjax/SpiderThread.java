/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.extension.spiderAjax;

import java.awt.EventQueue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Provider;

import org.apache.log4j.Logger;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.core.proxy.OverrideMessageProxyListener;
import org.parosproxy.paros.core.proxy.ProxyServer;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.Session;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpMalformedHeaderException;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpResponseHeader;
import org.parosproxy.paros.view.View;
import org.zaproxy.zap.extension.selenium.Browser;
import org.zaproxy.zap.extension.selenium.ExtensionSelenium;
import org.zaproxy.zap.network.HttpResponseBody;

import com.crawljax.browser.EmbeddedBrowser;
import com.crawljax.browser.WebDriverBackedEmbeddedBrowser;
import com.crawljax.core.CrawljaxRunner;
import com.crawljax.core.configuration.BrowserConfiguration;
import com.crawljax.core.configuration.CrawljaxConfiguration;
import com.crawljax.core.configuration.CrawljaxConfiguration.CrawljaxConfigurationBuilder;
import com.crawljax.core.configuration.ProxyConfiguration;
import com.crawljax.core.plugin.OnBrowserCreatedPlugin;
import com.crawljax.core.plugin.Plugins;
import com.google.common.collect.ImmutableSortedSet;
import com.google.inject.ProvisionException;

public class SpiderThread implements Runnable {

	private static final String LOCAL_PROXY_IP = "127.0.0.1";

	private final String displayName;
	private final AjaxSpiderTarget target;
	private CrawljaxRunner crawljax;
	private boolean running;
	private final Session session;
	private static final Logger logger = Logger.getLogger(SpiderThread.class);

	private HttpResponseHeader outOfScopeResponseHeader;
	private HttpResponseBody outOfScopeResponseBody;
	private List<SpiderListener> spiderListeners;
	private final List<String> exclusionList;
	private final String targetHost;
	private ProxyServer proxy;
	private int proxyPort;

	/**
	 * Constructs a {@code SpiderThread} for the given target.
	 * 
	 * @param displayName the name of the scan, must not be {@code null}.
	 * @param target the target, must not be {@code null}.
	 * @param extension the extension, must not be {@code null}.
	 * @param spiderListener the listener, must not be {@code null}.
	 */
	SpiderThread(String displayName, AjaxSpiderTarget target, ExtensionAjax extension, SpiderListener spiderListener) {
		this.displayName = displayName;
		this.target = target;
		this.running = false;
		spiderListeners = new ArrayList<>(2);
		spiderListeners.add(spiderListener);
		this.session = extension.getModel().getSession();
		this.exclusionList = session.getExcludeFromSpiderRegexs();
		this.targetHost = target.getStartUri().getHost();

		createOutOfScopeResponse(extension.getMessages().getString("spiderajax.outofscope.response"));

		proxy = new ProxyServer();
		proxy.setConnectionParam(extension.getModel().getOptionsParam().getConnectionParam());
		proxy.addOverrideMessageProxyListener(new SpiderProxyListener());
	}

	private void createOutOfScopeResponse(String response) {
		outOfScopeResponseBody = new HttpResponseBody();
		outOfScopeResponseBody.setBody(response.getBytes(StandardCharsets.UTF_8));

		final StringBuilder strBuilder = new StringBuilder(150);
		final String crlf = HttpHeader.CRLF;
		strBuilder.append("HTTP/1.1 403 Forbidden").append(crlf);
		strBuilder.append(HttpHeader.PRAGMA).append(": ").append("no-cache").append(crlf);
		strBuilder.append(HttpHeader.CACHE_CONTROL).append(": ").append("no-cache").append(crlf);
		strBuilder.append(HttpHeader.CONTENT_TYPE).append(": ").append("text/plain; charset=UTF-8").append(crlf);
		strBuilder.append(HttpHeader.CONTENT_LENGTH).append(": ").append(outOfScopeResponseBody.length()).append(crlf);

		HttpResponseHeader responseHeader;
		try {
			responseHeader = new HttpResponseHeader(strBuilder.toString());
		} catch (HttpMalformedHeaderException e) {
			logger.error("Failed to create a valid! response header: ", e);
			responseHeader = new HttpResponseHeader();
		}
		outOfScopeResponseHeader = responseHeader;
	}

	/**
	 * 
	 * @return the SpiderThread object
	 */
	public SpiderThread getSpiderThread() {
		return this;
	}
	
	/**
	 * 
	 * @return the SpiderThread object
	 */
	public boolean isRunning() {
		return this.running;
	}
	
	public CrawljaxConfiguration createCrawljaxConfiguration() {
		CrawljaxConfigurationBuilder configurationBuilder = CrawljaxConfiguration.builderFor(target.getStartUri().toString());

		configurationBuilder.setProxyConfig(ProxyConfiguration.manualProxyOn(LOCAL_PROXY_IP, proxyPort));

		configurationBuilder.setBrowserConfig(new BrowserConfiguration(
				com.crawljax.browser.EmbeddedBrowser.BrowserType.FIREFOX,
				target.getOptions().getNumberOfBrowsers(),
				new AjaxSpiderBrowserBuilder(target.getOptions().getBrowserId())));

		if (target.getOptions().isClickDefaultElems()) {
			configurationBuilder.crawlRules().clickDefaultElements();
		} else {
			for (String elem : target.getOptions().getElemsNames()) {
				configurationBuilder.crawlRules().click(elem);
			}
		}

		configurationBuilder.crawlRules().followExternalLinks(true);
		configurationBuilder.crawlRules().insertRandomDataInInputForms(target.getOptions().isRandomInputs());
		configurationBuilder.crawlRules().waitAfterEvent(target.getOptions().getEventWait(),TimeUnit.MILLISECONDS);
		configurationBuilder.crawlRules().waitAfterReloadUrl(target.getOptions().getReloadWait(),TimeUnit.MILLISECONDS);

		if (target.getOptions().getMaxCrawlStates() == 0) {
			configurationBuilder.setUnlimitedStates();
		} else {
			configurationBuilder.setMaximumStates(target.getOptions().getMaxCrawlStates());
		}
				
		configurationBuilder.setMaximumDepth(target.getOptions().getMaxCrawlDepth());
		configurationBuilder.setMaximumRunTime(target.getOptions().getMaxDuration(),TimeUnit.MINUTES);
		configurationBuilder.crawlRules().clickOnce(target.getOptions().isClickElemsOnce());
		
		configurationBuilder.addPlugin(DummyPlugin.DUMMY_PLUGIN);
				
		return configurationBuilder.build();
	}
	
	/**
	 * Instantiates the crawljax classes. 
	 */
	@Override
	public void run() {
		logger.info("Running Crawljax: " + displayName);
		this.running = true;
		notifyListenersSpiderStarted();
		logger.info("Starting proxy...");
		this.proxyPort = proxy.startServer(LOCAL_PROXY_IP, 0, true);
		logger.info("Proxy started, listening at port [" + proxyPort + "].");
		try {
			crawljax = new CrawljaxRunner(createCrawljaxConfiguration());
			crawljax.call();
        } catch (ProvisionException e) {
            logger.warn("Failed to start browser " + target.getOptions().getBrowserId(), e);
            if (View.isInitialised()) {
                ExtensionSelenium extSelenium = Control.getSingleton()
                        .getExtensionLoader()
                        .getExtension(ExtensionSelenium.class);
                Browser browser = Browser.getBrowserWithId(target.getOptions().getBrowserId());
                View.getSingleton().showWarningDialog(extSelenium.getWarnMessageFailedToStart(browser));
            }
		} catch (Exception e) {
			logger.error(e, e);
		} finally {
			this.running = false;
			logger.info("Stopping proxy...");
			stopProxy();
			logger.info("Proxy stopped.");
			notifyListenersSpiderStoped();
			logger.info("Finished Crawljax: " + displayName);
		}
	}

	private void stopProxy() {
		if (proxy != null) {
			proxy.stopServer();
			proxy = null;
		}
	}

	/**
	 * called by the buttons of the panel to stop the spider
	 */
	public void stopSpider() {
		crawljax.stop();
	}

	public void addSpiderListener(SpiderListener spiderListener) {
		spiderListeners.add(spiderListener);
	}

	public void removeSpiderListener(SpiderListener spiderListener) {
		spiderListeners.remove(spiderListener);
	}

	private void notifyListenersSpiderStarted() {
		for (SpiderListener listener : spiderListeners) {
			listener.spiderStarted();
		}
	}

	private void notifySpiderListenersFoundMessage(HistoryReference historyReference, HttpMessage httpMessage) {
		for (SpiderListener listener : spiderListeners) {
			listener.foundMessage(historyReference, httpMessage);
		}
	}

	private void notifyListenersSpiderStoped() {
		for (SpiderListener listener : spiderListeners) {
			listener.spiderStopped();
		}
	}

	private class SpiderProxyListener implements OverrideMessageProxyListener {

		@Override
		public int getArrangeableListenerOrder() {
			return 0;
		}

		@Override
		public boolean onHttpRequestSend(HttpMessage httpMessage) {
			boolean excluded = false;
			final String uri = httpMessage.getRequestHeader().getURI().toString();
			if (target.getContext() != null) {
				if (!target.getContext().isInContext(uri)) {
					logger.debug("Excluding request [" + uri + "] not in specified context.");
					excluded = true;
				}
			} else if (target.isInScopeOnly()) {
				if (!session.isInScope(uri)) {
					logger.debug("Excluding request [" + uri + "] not in scope.");
					excluded = true;
				}
			} else if (!targetHost.equalsIgnoreCase(httpMessage.getRequestHeader().getHostName())) {
				logger.debug("Excluding request [" + uri + "] not on target site [" + targetHost + "].");
				excluded = true;
			}
			if (!excluded) {
				for (String regex : exclusionList) {
					if (Pattern.matches(regex, uri)) {
						logger.debug("Excluding request [" + uri + "] matched regex [" + regex + "].");
						excluded = true;
					}
				}
			}

			if (excluded) {
				setOutOfScopeResponse(httpMessage);
				return true;
			}

			httpMessage.setRequestingUser(target.getUser());
			return false;
		}

		private void setOutOfScopeResponse(HttpMessage httpMessage) {
			try {
				httpMessage.setResponseHeader(outOfScopeResponseHeader.toString());
			} catch (HttpMalformedHeaderException ignore) {
				// Setting a valid response header.
			}
			httpMessage.setResponseBody(outOfScopeResponseBody.getBytes());
		}

		@Override
		public boolean onHttpResponseReceived(final HttpMessage httpMessage) {
			try {
				final HistoryReference historyRef = new HistoryReference(session, HistoryReference.TYPE_SPIDER_AJAX, httpMessage);
				historyRef.setCustomIcon("/resource/icon/10/spiderAjax.png", true);
				EventQueue.invokeLater(new Runnable() {

					@Override
					public void run() {
						session.getSiteTree().addPath(historyRef, httpMessage);
						notifySpiderListenersFoundMessage(historyRef, httpMessage);
					}
				});
			} catch (Exception e) {
				logger.error(e);
			}

			return false;
		}
	}

	// NOTE: The implementation of this class was copied from com.crawljax.browser.WebDriverBrowserBuilder since it's not
	// possible to correctly extend it because of DI issues.
	// Changes:
	// - Changed to use Selenium add-on to leverage the creation of WebDrivers.
	private static class AjaxSpiderBrowserBuilder implements Provider<EmbeddedBrowser> {

		@Inject
		private CrawljaxConfiguration configuration;
		@Inject
		private Plugins plugins;

		private final String browserId;

		public AjaxSpiderBrowserBuilder(String browserId) {
			super();
			this.browserId = browserId;
		}

		/**
		 * Build a new WebDriver based EmbeddedBrowser.
		 * 
		 * @return the new build WebDriver based embeddedBrowser
		 */
		@Override
		public EmbeddedBrowser get() {
			logger.debug("Setting up a Browser");
			// Retrieve the config values used
			ImmutableSortedSet<String> filterAttributes = configuration.getCrawlRules()
					.getPreCrawlConfig()
					.getFilterAttributeNames();
			long crawlWaitReload = configuration.getCrawlRules().getWaitAfterReloadUrl();
			long crawlWaitEvent = configuration.getCrawlRules().getWaitAfterEvent();

			EmbeddedBrowser embeddedBrowser = WebDriverBackedEmbeddedBrowser.withDriver(ExtensionSelenium.getWebDriver(
					Browser.getBrowserWithId(browserId),
					configuration.getProxyConfiguration().getHostname(),
					configuration.getProxyConfiguration().getPort()), filterAttributes, crawlWaitEvent, crawlWaitReload);
			plugins.runOnBrowserCreatedPlugins(embeddedBrowser);
			return embeddedBrowser;
		}
	}

	/**
	 * A {@link com.crawljax.core.plugin.Plugin} that does nothing, used only to suppress log warning when the
	 * {@link CrawljaxRunner} is started.
	 * 
	 * @see SpiderThread#createCrawljaxConfiguration()
	 * @see SpiderThread#run()
	 */
	private static class DummyPlugin implements OnBrowserCreatedPlugin {

		public static final DummyPlugin DUMMY_PLUGIN = new DummyPlugin();

		@Override
		public void onBrowserCreated(EmbeddedBrowser arg0) {
			// Nothing to do.
		}
	}
}