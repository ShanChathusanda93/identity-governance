/*
 *
 *  Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.wso2.carbon.identity.user.endpoint.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.recovery.IdentityRecoveryClientException;
import org.wso2.carbon.identity.recovery.IdentityRecoveryConstants;
import org.wso2.carbon.identity.recovery.IdentityRecoveryException;
import org.wso2.carbon.identity.recovery.bean.NotificationResponseBean;
import org.wso2.carbon.identity.recovery.signup.UserSelfRegistrationManager;
import org.wso2.carbon.identity.user.endpoint.Constants;
import org.wso2.carbon.identity.user.endpoint.MeApiService;
import org.wso2.carbon.identity.user.endpoint.dto.SelfUserRegistrationRequestDTO;
import org.wso2.carbon.identity.user.endpoint.dto.SuccessfulUserCreationDTO;
import org.wso2.carbon.identity.user.endpoint.util.Utils;
import org.wso2.carbon.identity.user.export.core.UserExportException;
import org.wso2.carbon.user.core.util.UserCoreUtil;

import java.util.Map;
import javax.ws.rs.core.Response;

public class MeApiServiceImpl extends MeApiService {

    private static final Log LOG = LogFactory.getLog(MeApiServiceImpl.class);

    // Default value for enabling API response.
    private static final boolean ENABLE_DETAILED_API_RESPONSE = false;

    @Override
    public Response getMe() {

        String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        String userStoreDomain = UserCoreUtil.extractDomainFromName(username);
        username = UserCoreUtil.removeDomainFromName(username);
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        Map userAttributes;
        try {
            userAttributes = Utils.getUserInformationService().getRetainedUserInformation(username, userStoreDomain,
                    tenantId);
        } catch (UserExportException e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
        return Response.ok().status(Response.Status.OK).entity(userAttributes).build();
    }

    @Override
    public Response mePost(SelfUserRegistrationRequestDTO selfUserRegistrationRequestDTO) {

        String tenantFromContext = (String) IdentityUtil.threadLocalProperties.get().get(Constants.TENANT_NAME_FROM_CONTEXT);

        if (StringUtils.isNotBlank(tenantFromContext)) {
            selfUserRegistrationRequestDTO.getUser().setTenantDomain(tenantFromContext);
        }

        if (selfUserRegistrationRequestDTO != null && StringUtils.isBlank(selfUserRegistrationRequestDTO.getUser().getRealm())) {
            selfUserRegistrationRequestDTO.getUser().setRealm(IdentityUtil.getPrimaryDomainName());
        }

        UserSelfRegistrationManager userSelfRegistrationManager = Utils
                .getUserSelfRegistrationManager();
        NotificationResponseBean notificationResponseBean = null;
        try {
            notificationResponseBean = userSelfRegistrationManager
                    .registerUser(Utils.getUser(selfUserRegistrationRequestDTO.getUser()),
                            selfUserRegistrationRequestDTO.getUser().getPassword(),
                            Utils.getClaims(selfUserRegistrationRequestDTO.getUser().getClaims()),
                            Utils.getProperties(selfUserRegistrationRequestDTO.getProperties()));
        } catch (IdentityRecoveryClientException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Client Error while registering self up user ", e);
            }
            if (IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_USER_ALREADY_EXISTS.getCode().equals(e.getErrorCode())) {
                Utils.handleConflict(e.getMessage(), e.getErrorCode());
            } else {
                Utils.handleBadRequest(e.getMessage(), e.getErrorCode());
            }
        } catch (IdentityRecoveryException e) {
            Utils.handleInternalServerError(Constants.SERVER_ERROR, e.getErrorCode(), LOG, e);
        } catch (Throwable throwable) {
            Utils.handleInternalServerError(Constants.SERVER_ERROR, IdentityRecoveryConstants
                    .ErrorMessages.ERROR_CODE_UNEXPECTED.getCode(), LOG, throwable);
        }
        return buildSuccessfulAPIResponse(notificationResponseBean);
    }

    /**
     * Build response for a successful user self registration.
     *
     * @param notificationResponseBean NotificationResponseBean {@link NotificationResponseBean}
     * @return Response
     */
    private Response buildSuccessfulAPIResponse(NotificationResponseBean notificationResponseBean) {

        // Check whether detailed api responses are enabled.
        if (isDetailedResponseBodyEnabled()) {
            SuccessfulUserCreationDTO successfulUserCreationDTO = buildSuccessResponse(notificationResponseBean);
            return Response.status(Response.Status.CREATED).entity(successfulUserCreationDTO).build();
        } else {
            if (notificationResponseBean != null) {
                String notificationChannel = notificationResponseBean.getNotificationChannel();
                // If the notifications are required in the form of legacy response, and notifications are externally
                // managed, the recoveryId should be in the response as text.
                if (StringUtils.isNotEmpty(notificationChannel) && notificationChannel
                        .equals(Constants.EXTERNAL_NOTIFICATION_CHANNEL)) {
                    return Response.status(Response.Status.CREATED).entity(notificationResponseBean.getRecoveryId())
                            .build();
                }
            }
            return Response.status(Response.Status.CREATED).build();
        }
    }

    /**
     * Build the successResponseDTO for successful user identification and channel retrieve.
     *
     * @param notificationResponseBean NotificationResponseBean
     * @return SuccessfulUserCreationDTO
     */
    private SuccessfulUserCreationDTO buildSuccessResponse(NotificationResponseBean notificationResponseBean) {

        SuccessfulUserCreationDTO successDTO = new SuccessfulUserCreationDTO();
        successDTO.setCode(notificationResponseBean.getCode());
        successDTO.setMessage(notificationResponseBean.getMessage());
        successDTO.setNotificationChannel(notificationResponseBean.getNotificationChannel());
        successDTO.setConfirmationCode(notificationResponseBean.getRecoveryId());
        return successDTO;
    }

    /**
     * Reads configurations from the identity.xml and return whether the detailed response is enabled or not.
     *
     * @return True if the legacy response is enabled.
     */
    private boolean isDetailedResponseBodyEnabled() {

        String enableDetailedResponseConfig= IdentityUtil
                .getProperty(Constants.ENABLE_DETAILED_API_RESPONSE);
        if(StringUtils.isEmpty(enableDetailedResponseConfig)){
            // Return false if the user has not enabled the detailed response body.
            return ENABLE_DETAILED_API_RESPONSE;
        } else  {
            return Boolean.parseBoolean(enableDetailedResponseConfig);
        }
    }
}

