/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Copyright 2013 The ZAP Development Team
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	 http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.zap.extension.spiderAjax;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.sf.json.JSONObject;

import org.apache.log4j.Logger;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.db.DatabaseException;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.model.SiteNode;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.extension.api.ApiAction;
import org.zaproxy.zap.extension.api.ApiException;
import org.zaproxy.zap.extension.api.ApiException.Type;
import org.zaproxy.zap.extension.users.ExtensionUserManagement;
import org.zaproxy.zap.model.Context;
import org.zaproxy.zap.users.User;
import org.zaproxy.zap.utils.ApiUtils;
import org.zaproxy.zap.extension.api.ApiImplementor;
import org.zaproxy.zap.extension.api.ApiResponse;
import org.zaproxy.zap.extension.api.ApiResponseConversionUtils;
import org.zaproxy.zap.extension.api.ApiResponseElement;
import org.zaproxy.zap.extension.api.ApiResponseList;
import org.zaproxy.zap.extension.api.ApiView;

public class AjaxSpiderAPI extends ApiImplementor implements SpiderListener {

	private static final Logger logger = Logger.getLogger(AjaxSpiderAPI.class);

	private static final String PREFIX = "ajaxSpider";

	private static final String ACTION_START_SCAN = "scan";
	private static final String ACTION_START_SCAN_AS_USER = "scanAsUser";
	private static final String ACTION_STOP_SCAN = "stop";

	private static final String VIEW_STATUS = "status";
	private static final String VIEW_RESULTS = "results";
	private static final String VIEW_NUMBER_OF_RESULTS = "numberOfResults";

	private static final String PARAM_CONTEXT_NAME = "contextName";
	private static final String PARAM_URL = "url";
	private static final String PARAM_USER_NAME = "userName";
	private static final String PARAM_IN_SCOPE = "inScope";
	private static final String PARAM_START = "start";
	private static final String PARAM_COUNT = "count";

	private enum SpiderStatus {
		STOPPED,
		RUNNING;

		@Override
		public String toString() {
			return super.toString().toLowerCase();
		}
	}

	private final ExtensionAjax extension;

	private List<HistoryReference> historyReferences;
	private SpiderThread spiderThread;

	public AjaxSpiderAPI(ExtensionAjax extension) {
		this.extension = extension;
		this.historyReferences = Collections.emptyList();

		ApiAction scan = new ApiAction(ACTION_START_SCAN, null, new String[] { PARAM_URL, PARAM_IN_SCOPE, PARAM_CONTEXT_NAME });
		scan.setDescriptionTag("spiderajax.api.action.scan");
		this.addApiAction(scan);

		ApiAction scanAsUser = new ApiAction(
				ACTION_START_SCAN_AS_USER,
				new String[] { PARAM_CONTEXT_NAME, PARAM_USER_NAME },
				new String[] { PARAM_URL });
		scanAsUser.setDescriptionTag("spiderajax.api.action.scanAsUser");
		this.addApiAction(scanAsUser);
		this.addApiAction(new ApiAction(ACTION_STOP_SCAN));

		this.addApiView(new ApiView(VIEW_STATUS));
		this.addApiView(new ApiView(VIEW_RESULTS, null, new String[] { PARAM_START, PARAM_COUNT }));
		this.addApiView(new ApiView(VIEW_NUMBER_OF_RESULTS));

	}

	@Override
	public String getPrefix() {
		return PREFIX;
	}

	@Override
	public ApiResponse handleApiAction(String name, JSONObject params) throws ApiException {
		Context context = null;

		switch (name) {
		case ACTION_START_SCAN:
			if (extension.isSpiderRunning()) {
				throw new ApiException(ApiException.Type.SCAN_IN_PROGRESS);
			}

			String url = ApiUtils.getOptionalStringParam(params, PARAM_URL);
			if (params.containsKey(PARAM_CONTEXT_NAME)) {
				String contextName = params.getString(PARAM_CONTEXT_NAME);
				if (!contextName.isEmpty()) {
					context = ApiUtils.getContextByName(contextName);
				}
			}
			startScan(url, null, context, getParam(params, PARAM_IN_SCOPE, false));
			break;

		case ACTION_START_SCAN_AS_USER:
			if (extension.isSpiderRunning()) {
				throw new ApiException(ApiException.Type.SCAN_IN_PROGRESS);
			}

			String urlUserScan = ApiUtils.getOptionalStringParam(params, PARAM_URL);
			String userName = ApiUtils.getNonEmptyStringParam(params, PARAM_USER_NAME);
			context = ApiUtils.getContextByName(params, PARAM_CONTEXT_NAME);
			User user = getUser(context, userName);
			if (user == null) {
				throw new ApiException(Type.USER_NOT_FOUND, PARAM_USER_NAME);
			}

			startScan(urlUserScan, user, context, getParam(params, PARAM_IN_SCOPE, false));
			break;

		case ACTION_STOP_SCAN:
			stopSpider();
			break;
		default:
			throw new ApiException(ApiException.Type.BAD_ACTION);
		}
		return ApiResponseElement.OK;
	}

	/**
	 * Gets the user with the given name from the given context.
	 *
	 * @param context the context the user belongs too
	 * @param userName the name of the user
	 * @return the user, or {@code null} if not found.
	 * @throws ApiException if the {@code ExtensionUserManagement} is not enabled.
	 */
	private User getUser(Context context, String userName) throws ApiException {
		ExtensionUserManagement usersExtension = (ExtensionUserManagement) Control.getSingleton()
				.getExtensionLoader()
				.getExtension(ExtensionUserManagement.NAME);
		if (usersExtension == null) {
			throw new ApiException(Type.NO_IMPLEMENTOR, ExtensionUserManagement.NAME);
		}
		List<User> users = usersExtension.getContextUserAuthManager(context.getIndex()).getUsers();
		for (User user : users) {
			if (userName.equals(user.getName())) {
				return user;
			}
		}
		return null;
	}

	/**
	 * Starts the spider with the given data.
	 *
	 * @param url the starting URL
	 * @param user the user, might be {@code null} if spidering a context with URLs already accessed.
	 * @param context the context to spider, might be {@code null}.
	 * @param inScopeOnly if should just spider in scope. Spider of context takes precedence.
	 * @throws ApiException if there's an error with the data provided, for example, no starting URL when one is required.
	 */
	private void startScan(String url, User user, Context context, boolean inScopeOnly) throws ApiException {
		URI startURI = null;
		boolean validateUrl = true;
		if (url == null || url.isEmpty()) {
			if (context == null || context.getNodesInContextFromSiteTree().isEmpty()) {
				throw new ApiException(Type.MISSING_PARAMETER, PARAM_URL);
			}

			List<SiteNode> nodes = context.getNodesInContextFromSiteTree();
			if (nodes.isEmpty()) {
				throw new ApiException(Type.MISSING_PARAMETER, PARAM_URL);
			}

			startURI = URI.create(nodes.get(0).getHistoryReference().getURI().toString());
			validateUrl = false;
		} else if (context != null && !context.isInContext(url)) {
			throw new ApiException(Type.URL_NOT_IN_CONTEXT, PARAM_URL);
		}

		if (validateUrl) {
			try {
				startURI = new URI(url);
			} catch (URISyntaxException e) {
				throw new ApiException(ApiException.Type.ILLEGAL_PARAMETER, PARAM_URL, e);
			}
			String scheme = startURI.getScheme();
			if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
				throw new ApiException(ApiException.Type.ILLEGAL_PARAMETER, PARAM_URL);
			}
		}

		switch (Control.getSingleton().getMode()) {
		case safe:
			throw new ApiException(getModeViolationType());
		case protect:
			if ((validateUrl && !Model.getSingleton().getSession().isInScope(url))
					|| (context != null && !context.isInScope())) {
				throw new ApiException(getModeViolationType());
			}
			// No problem
			break;
		case attack:
		case standard:
		default:
			// No problem
			break;
		}

		AjaxSpiderTarget.Builder targetBuilder = AjaxSpiderTarget.newBuilder(extension.getModel().getSession())
				.setInScopeOnly(inScopeOnly)
				.setOptions(extension.getAjaxSpiderParam())
				.setStartUri(startURI);

		if (user != null) {
			targetBuilder.setUser(user);
		} else if (context != null) {
			targetBuilder.setContext(context);
		}

		AjaxSpiderTarget target = targetBuilder.build();
		String displayName = "API - " + extension.createDisplayName(target);
		spiderThread = extension.createSpiderThread(displayName, target, this);

		try {
			new Thread(spiderThread).start();
		} catch (Exception e) {
			logger.error(e);
		}
	}

	// XXX replace calls with ApiException.Type.MODE_VIOLATION once targeting newer ZAP version (>= 2.5.0)
	private static ApiException.Type getModeViolationType() {
		try {
			return ApiException.Type.valueOf("MODE_VIOLATION");
		} catch (IllegalArgumentException e) {
			return ApiException.Type.ILLEGAL_PARAMETER;
		}
	}

	@Override
	public ApiResponse handleApiView(String name, JSONObject params) throws ApiException {
		ApiResponse result;
		switch (name) {
		case VIEW_STATUS:
			result = new ApiResponseElement(name, isSpiderRunning()
					? SpiderStatus.RUNNING.toString()
					: SpiderStatus.STOPPED.toString());
			break;
		case VIEW_RESULTS:
			try {
				int start = this.getParam(params, PARAM_START, 1);
				if (start <= 0) {
					start = 1;
				}
				final int count = this.getParam(params, PARAM_COUNT, 0);
				final boolean hasEnd = count > 0;
				final int finalRecord = !hasEnd ? 0 : (start > 0 ? start + count - 1 : count);

				final ApiResponseList resultList = new ApiResponseList(name);
				for (int i = start - 1, recordsProcessed = i; i < historyReferences.size(); ++i) {
					HistoryReference historyReference = historyReferences.get(i);
					resultList.addItem(ApiResponseConversionUtils.httpMessageToSet(historyReference.getHistoryId(), historyReference.getHttpMessage()));

					if (hasEnd) {
						++recordsProcessed;
						if (recordsProcessed >= finalRecord) {
							break;
						}
					}
				}
				result = resultList;
			} catch (DatabaseException | IOException e) {
				throw new ApiException(ApiException.Type.INTERNAL_ERROR, e);
			}
			break;
		case VIEW_NUMBER_OF_RESULTS:
			result = new ApiResponseElement(name, String.valueOf(historyReferences.size()));
			break;
		default:
			throw new ApiException(ApiException.Type.BAD_VIEW);
		}
		return result;
	}

	private boolean isSpiderRunning() {
		return (extension.isSpiderRunning() && spiderThread != null);
	}

	private void stopSpider() {
		if (isSpiderRunning()) {
			spiderThread.stopSpider();
			spiderThread = null;
		}
	}

	@Override
	public void spiderStarted() {
		historyReferences = new ArrayList<>();
	}

	@Override
	public void foundMessage(HistoryReference historyReference, HttpMessage httpMessage) {
		historyReferences.add(historyReference);
	}

	@Override
	public void spiderStopped() {
	}

	void reset() {
		stopSpider();
		historyReferences = Collections.emptyList();
	}
}
