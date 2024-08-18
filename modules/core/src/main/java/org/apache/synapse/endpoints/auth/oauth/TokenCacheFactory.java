/*
 * Copyright (c) 2024, WSO2 LLC. (https://www.wso2.com/).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.synapse.endpoints.auth.oauth;

import org.apache.synapse.SynapseException;
import org.apache.synapse.endpoints.auth.AuthConstants;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class TokenCacheFactory {

    public static TokenCache getTokenCache(String classPath) {
        if (classPath != null && classPath.contains(AuthConstants.REDIS_TOKEN_CACHE)) {
            // Loads the RedisTokenCache class and returns its singleton instance
            Class<?> clazz;
            try {
                clazz = Class.forName(classPath);
            } catch (ClassNotFoundException e) {
                throw new SynapseException("Error loading class: " + AuthConstants.REDIS_TOKEN_CACHE, e);
            }

            Method getInstanceMethod;
            try {
                getInstanceMethod = clazz.getMethod("getInstance");
            } catch (NoSuchMethodException e) {
                throw new SynapseException("Error loading instance method for class: " + AuthConstants.REDIS_TOKEN_CACHE, e);
            }

            try {
                return (TokenCache) getInstanceMethod.invoke(null);
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new SynapseException("Error invoking instance method for class: " + AuthConstants.REDIS_TOKEN_CACHE, e);
            }
        }
        // Return default token cache if the token cache class is not defined
        return LocalTokenCache.getInstance();
    }
}
