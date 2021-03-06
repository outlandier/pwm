/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2016 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.http.servlet.peoplesearch;

import com.novell.ldapchai.exception.ChaiUnavailableException;
import password.pwm.*;
import password.pwm.bean.UserIdentity;
import password.pwm.config.FormConfiguration;
import password.pwm.config.PwmSetting;
import password.pwm.error.*;
import password.pwm.http.HttpMethod;
import password.pwm.http.PwmHttpRequestWrapper;
import password.pwm.http.PwmRequest;
import password.pwm.http.PwmRequestFlag;
import password.pwm.http.servlet.AbstractPwmServlet;
import password.pwm.svc.stats.Statistic;
import password.pwm.svc.stats.StatisticsManager;
import password.pwm.util.JsonUtil;
import password.pwm.util.Validator;
import password.pwm.util.logging.PwmLogger;
import password.pwm.ws.server.RestResultBean;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

@WebServlet(
        name="PeopleSearchServlet",
        urlPatterns = {
                PwmConstants.URL_PREFIX_PRIVATE + "/peoplesearch",
                PwmConstants.URL_PREFIX_PUBLIC + "/peoplesearch",
                PwmConstants.URL_PREFIX_PRIVATE + "/PeopleSearch",
                PwmConstants.URL_PREFIX_PUBLIC + "/PeopleSearch",
        }
)
public class PeopleSearchServlet extends AbstractPwmServlet {

    private static final PwmLogger LOGGER = PwmLogger.forClass(PeopleSearchServlet.class);

    private static final String PARAM_USERKEY = "userKey";

    public enum PeopleSearchActions implements ProcessAction {
        search(HttpMethod.POST),
        detail(HttpMethod.POST),
        photo(HttpMethod.GET),
        clientData(HttpMethod.GET),
        orgChartData(HttpMethod.POST),

        ;

        private final HttpMethod method;

        PeopleSearchActions(HttpMethod method) {
            this.method = method;
        }

        public Collection<HttpMethod> permittedMethods() {
            return Collections.singletonList(method);
        }
    }

    protected PeopleSearchActions readProcessAction(final PwmRequest request)
            throws PwmUnrecoverableException {
        try {
            return PeopleSearchActions.valueOf(request.readParameterAsString(PwmConstants.PARAM_ACTION_REQUEST));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    protected void processAction(
            final PwmRequest pwmRequest
    )
            throws ServletException, IOException, ChaiUnavailableException, PwmUnrecoverableException
    {
        if (!pwmRequest.getConfig().readSettingAsBoolean(PwmSetting.PEOPLE_SEARCH_ENABLE)) {
            pwmRequest.respondWithError(new ErrorInformation(PwmError.ERROR_SERVICE_NOT_AVAILABLE));
            return;
        }

        if (pwmRequest.getURL().isPublicUrl()) {
            if (!pwmRequest.getConfig().readSettingAsBoolean(PwmSetting.PEOPLE_SEARCH_ENABLE_PUBLIC)) {
                pwmRequest.respondWithError(new ErrorInformation(PwmError.ERROR_SERVICE_NOT_AVAILABLE,"public peoplesearch service is not enabled"));
                return;
            }
        } else {
            if (!pwmRequest.getPwmSession().getSessionManager().checkPermission(pwmRequest.getPwmApplication(), Permission.PEOPLE_SEARCH)) {
                pwmRequest.respondWithError(new ErrorInformation(PwmError.ERROR_UNAUTHORIZED));
                return;
            }

        }

        final PeopleSearchConfiguration peopleSearchConfiguration = new PeopleSearchConfiguration(pwmRequest.getConfig());

        final PeopleSearchActions peopleSearchAction = this.readProcessAction(pwmRequest);
        if (peopleSearchAction != null) {
            switch (peopleSearchAction) {
                case search:
                    restSearchRequest(pwmRequest);
                    return;

                case detail:
                    restUserDetailRequest(pwmRequest, peopleSearchConfiguration);
                    return;

                case photo:
                    processUserPhotoImageRequest(pwmRequest);
                    return;

                case clientData:
                    restLoadClientData(pwmRequest, peopleSearchConfiguration);
                    return;

                case orgChartData:
                    restOrgChartData(pwmRequest, peopleSearchConfiguration);
                    return;
            }
        }

        if (pwmRequest.getURL().isPublicUrl()) {
            pwmRequest.setFlag(PwmRequestFlag.HIDE_IDLE, true);
            pwmRequest.setFlag(PwmRequestFlag.NO_IDLE_TIMEOUT, true);
        }
        pwmRequest.forwardToJsp(PwmConstants.JSP_URL.PEOPLE_SEARCH);
    }

    private void restLoadClientData(
            final PwmRequest pwmRequest,
            final PeopleSearchConfiguration peopleSearchConfiguration

    )
            throws ChaiUnavailableException, PwmUnrecoverableException, IOException, ServletException
    {

        final Map<String, String> searchColumns = new LinkedHashMap<>();
        final List<FormConfiguration> searchForm = pwmRequest.getConfig().readSettingAsForm(PwmSetting.PEOPLE_SEARCH_RESULT_FORM);
        for (final FormConfiguration formConfiguration : searchForm) {
            searchColumns.put(formConfiguration.getName(),
                    formConfiguration.getLabel(pwmRequest.getLocale()));
        }

        final PeopleSearchClientConfigBean peopleSearchClientConfigBean = new PeopleSearchClientConfigBean();
        peopleSearchClientConfigBean.setPeoplesearch_search_columns(searchColumns);
        peopleSearchClientConfigBean.setPeoplesearch_enablePhoto(peopleSearchConfiguration.isPhotosEnabled());
        peopleSearchClientConfigBean.setPeoplesearch_orgChartEnabled(peopleSearchConfiguration.isOrgChartEnabled());

        final RestResultBean restResultBean = new RestResultBean(peopleSearchClientConfigBean);
        LOGGER.trace(pwmRequest, "returning clientData: " + JsonUtil.serialize(restResultBean));
        pwmRequest.outputJsonResult(restResultBean);
    }


    private void restSearchRequest(
            final PwmRequest pwmRequest
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, IOException, ServletException
    {
        final String bodyString = pwmRequest.readRequestBodyAsString();
        final Map<String, String> valueMap = JsonUtil.deserializeStringMap(bodyString);

        final String username = Validator.sanitizeInputValue(pwmRequest.getConfig(), valueMap.get("username"), 1024);
        final boolean includeDisplayName = pwmRequest.readParameterAsBoolean("includeDisplayName");

        // if not in cache, build results from ldap
        final PeopleSearchDataReader peopleSearchDataReader = new PeopleSearchDataReader(pwmRequest);
        final SearchResultBean searchResultBean = peopleSearchDataReader.makeSearchResultBean(username, includeDisplayName);
        searchResultBean.setFromCache(false);
        final RestResultBean restResultBean = new RestResultBean(searchResultBean);
        pwmRequest.outputJsonResult(restResultBean);
        LOGGER.trace(pwmRequest, "returning " + searchResultBean.getSearchResults().size() + " results for search request '" + username + "'");
    }



    private void restOrgChartData(
            final PwmRequest pwmRequest,
            final PeopleSearchConfiguration peopleSearchConfiguration
    )
            throws IOException, PwmUnrecoverableException, ServletException
    {
        if (!peopleSearchConfiguration.isOrgChartEnabled()) {
            throw new PwmUnrecoverableException(PwmError.ERROR_SERVICE_NOT_AVAILABLE);
        }

        final Map<String, String> requestInputMap = pwmRequest.readBodyAsJsonStringMap();
        if (requestInputMap == null) {
            return;
        }

        final UserIdentity userIdentity;
        {
            final String userKey = requestInputMap.get(PARAM_USERKEY);
            if (userKey == null || userKey.isEmpty()) {
                userIdentity = pwmRequest.getUserInfoIfLoggedIn();
                if (userIdentity == null) {
                    return;
                }
            } else {
                userIdentity = UserIdentity.fromObfuscatedKey(userKey, pwmRequest.getPwmApplication());
            }
        }

        try {
            final PeopleSearchDataReader peopleSearchDataReader = new PeopleSearchDataReader(pwmRequest);
            final OrgChartDataBean orgChartData = peopleSearchDataReader.makeOrgChartData(userIdentity);
            pwmRequest.outputJsonResult(new RestResultBean(orgChartData));
            StatisticsManager.incrementStat(pwmRequest, Statistic.PEOPLESEARCH_ORGCHART);
        } catch (PwmException e) {
            LOGGER.error(pwmRequest, "error generating user detail object: " + e.getMessage());
            pwmRequest.respondWithError(e.getErrorInformation());
        }
    }


    private void restUserDetailRequest(
            final PwmRequest pwmRequest,
            final PeopleSearchConfiguration peopleSearchConfiguration
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, IOException, ServletException
    {
        final Map<String, String> valueMap = pwmRequest.readBodyAsJsonStringMap();

        if (valueMap == null) {
            return;
        }

        final String userKey = valueMap.get(PARAM_USERKEY);
        if (userKey == null || userKey.isEmpty()) {
            return;
        }

        try {
            final PeopleSearchDataReader peopleSearchDataReader = new PeopleSearchDataReader(pwmRequest);
            final UserDetailBean detailData = peopleSearchDataReader.makeUserDetailRequest(userKey);
            pwmRequest.outputJsonResult(new RestResultBean(detailData));
            pwmRequest.getPwmApplication().getStatisticsManager().incrementValue(Statistic.PEOPLESEARCH_DETAILS);
        } catch (PwmOperationalException e) {
            LOGGER.error(pwmRequest, "error generating user detail object: " + e.getMessage());
            pwmRequest.respondWithError(e.getErrorInformation());
        }
    }

    private void processUserPhotoImageRequest(final PwmRequest pwmRequest)
            throws ChaiUnavailableException, PwmUnrecoverableException, IOException, ServletException
    {
        final String userKey = pwmRequest.readParameterAsString(PARAM_USERKEY, PwmHttpRequestWrapper.Flag.BypassValidation);
        if (userKey.length() < 1) {
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_MISSING_PARAMETER, PARAM_USERKEY + " parameter is missing");
            LOGGER.error(pwmRequest, errorInformation);
            pwmRequest.respondWithError(errorInformation, false);
            return;
        }


        final PeopleSearchDataReader peopleSearchDataReader = new PeopleSearchDataReader(pwmRequest);
        final UserIdentity userIdentity = UserIdentity.fromKey(userKey, pwmRequest.getPwmApplication());
        try {
            peopleSearchDataReader.checkIfUserIdentityViewable(userIdentity);
        } catch (PwmOperationalException e) {
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNAUTHORIZED, "error during photo request while checking if requested userIdentity is within search scope: " + e.getMessage());
            LOGGER.error(pwmRequest, errorInformation);
            pwmRequest.respondWithError(errorInformation, false);
            return;
        }

        LOGGER.debug(pwmRequest, "received user photo request to view user " + userIdentity.toString());

        final PhotoDataBean photoData;
        try {
            photoData = peopleSearchDataReader.readPhotoDataFromLdap(userIdentity);
        } catch (PwmOperationalException e) {
            final ErrorInformation errorInformation = e.getErrorInformation();
            LOGGER.error(pwmRequest, errorInformation);
            pwmRequest.respondWithError(errorInformation, false);
            return;
        }

        OutputStream outputStream = null;
        try {
            final long maxCacheSeconds = pwmRequest.getConfig().readSettingAsLong(PwmSetting.PEOPLE_SEARCH_MAX_CACHE_SECONDS);
            final HttpServletResponse resp = pwmRequest.getPwmResponse().getHttpServletResponse();
            resp.setContentType(photoData.getMimeType());
            resp.setDateHeader("Expires", System.currentTimeMillis() + (maxCacheSeconds * 1000l));
            resp.setHeader("Cache-Control", "public, max-age=" + maxCacheSeconds);

            outputStream = pwmRequest.getPwmResponse().getOutputStream();
            outputStream.write(photoData.getContents());

        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }
    }
}
