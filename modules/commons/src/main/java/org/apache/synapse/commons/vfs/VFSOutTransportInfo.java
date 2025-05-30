/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.commons.vfs;

import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.OutTransportInfo;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.provider.UriParser;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * The VFS OutTransportInfo is a holder of information to send an outgoing message
 * (e.g. a Response) to a VFS destination. Thus at a minimum a reference to a
 * File URI (i.e. directory or a file) are held
 */

public class VFSOutTransportInfo implements OutTransportInfo {

    private static final Log log = LogFactory.getLog(VFSOutTransportInfo.class);

    private String outFileURI = null;
    private String outFileName = null;
    private String contentType = null;
    private int maxRetryCount = 3;
    private long reconnectTimeout = 30000;
    private boolean append;
    private boolean fileLocking;
    private Map<String, String> fso = null;
    private boolean sendFileSynchronously = false;
    //When the folder structure does not exists forcefully create
    private boolean forceCreateFolder = false;
    private boolean updateLastModified = true;
    
    private static final String[] uriParamsToDelete = {VFSConstants.APPEND+"=true", VFSConstants.APPEND+"=false"};

    /**
     * Constructs the VFSOutTransportInfo containing the information about the file to which the
     * response has to be submitted to.
     * 
     * @param outFileURI URI of the file to which the message is delivered
     */
    public VFSOutTransportInfo(String outFileURI, boolean fileLocking) {

     	if (outFileURI.startsWith(VFSConstants.VFS_PREFIX)) {
            String vfsURI = outFileURI.substring(VFSConstants.VFS_PREFIX.length());
            String queryParams = UriParser.extractQueryString(new StringBuilder(vfsURI));

            //Lets get rid of unwanted query params and clean the URI
            if(null != queryParams && !"".equals(queryParams) && vfsURI.contains(VFSConstants.APPEND)) {
               this.outFileURI = cleanURI(vfsURI, queryParams, outFileURI);
            } else {
                this.outFileURI = vfsURI;
            }
        } else {
            this.outFileURI = outFileURI;
        }
     	
        Map<String,String> properties = BaseUtils.getEPRProperties(outFileURI);

        String scheme = UriParser.extractScheme(this.outFileURI);
        properties.put(VFSConstants.SCHEME, scheme);
        setOutFileSystemOptionsMap(properties);

        if (properties.containsKey(VFSConstants.SUBFOLDER_TIMESTAMP)) {
            String strSubfolderFormat = properties.get(VFSConstants.SUBFOLDER_TIMESTAMP);
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(strSubfolderFormat);
                String strDateformat = sdf.format(new Date());
                int iIndex = this.outFileURI.indexOf("?");
                if (iIndex > -1) {
                    this.outFileURI = this.outFileURI.substring(0, iIndex) + strDateformat
                            + this.outFileURI.substring(iIndex, this.outFileURI.length());
                }else{
                    this.outFileURI += strDateformat;
                }
            } catch (Exception e) {
                log.warn("Error generating subfolder name with date", e);
            }
        }       
        
        if (properties.containsKey(VFSConstants.MAX_RETRY_COUNT)) {
            String strMaxRetryCount = properties.get(VFSConstants.MAX_RETRY_COUNT);
            maxRetryCount = Integer.parseInt(strMaxRetryCount);
        } else {
            maxRetryCount = VFSConstants.DEFAULT_MAX_RETRY_COUNT;
        }

        forceCreateFolder = false;
        if (properties.containsKey(VFSConstants.FORCE_CREATE_FOLDER)) {
            String strForceCreateFolder = properties.get(VFSConstants.FORCE_CREATE_FOLDER);
            if (strForceCreateFolder != null && strForceCreateFolder.toLowerCase().equals("true")) {
                forceCreateFolder = true;
            }
        }
        
        if (properties.containsKey(VFSConstants.RECONNECT_TIMEOUT)) {
            String strReconnectTimeout = properties.get(VFSConstants.RECONNECT_TIMEOUT);
            reconnectTimeout = Long.parseLong(strReconnectTimeout) * 1000;
        } else {
            reconnectTimeout = VFSConstants.DEFAULT_RECONNECT_TIMEOUT;
        }

        if (properties.containsKey(VFSConstants.TRANSPORT_FILE_LOCKING)) {
            String strFileLocking = properties.get(VFSConstants.TRANSPORT_FILE_LOCKING);
            if (VFSConstants.TRANSPORT_FILE_LOCKING_ENABLED.equals(strFileLocking)) {
                this.fileLocking = true;
            } else if (VFSConstants.TRANSPORT_FILE_LOCKING_DISABLED.equals(strFileLocking)) {
                this.fileLocking = false;
            }
        } else {
            this.fileLocking = fileLocking;
        }

        if (properties.containsKey(VFSConstants.TRANSPORT_FILE_SEND_FILE_LOCKING)) {
            String strSendLocking = properties.get(VFSConstants.TRANSPORT_FILE_SEND_FILE_LOCKING);
            sendFileSynchronously = Boolean.parseBoolean(strSendLocking);
        } else {
            sendFileSynchronously = false;
        }

        if (properties.containsKey(VFSConstants.APPEND)) {
            String strAppend = properties.get(VFSConstants.APPEND);
            append = Boolean.parseBoolean(strAppend);
        }

        if (properties.containsKey(VFSConstants.UPDATE_LAST_MODIFIED)) {
            String strUpdateLastModified = properties.get(VFSConstants.UPDATE_LAST_MODIFIED);
            updateLastModified = Boolean.parseBoolean(strUpdateLastModified);
        }

        if (log.isDebugEnabled()) {
            log.debug("Using the fileURI        : " + this.outFileURI);
            log.debug("Using the maxRetryCount  : " + maxRetryCount);
            log.debug("Using the reconnectionTimeout : " + reconnectTimeout);
            log.debug("Using the append         : " + append);
            log.debug("File locking             : " + (this.fileLocking ? "ON" : "OFF"));
        }
    }

    private String cleanURI(String vfsURI, String queryParams, String originalFileURI) {
        // Using Apache Commons StringUtils and Java StringBuilder for improved performance.
        vfsURI = StringUtils.replace(vfsURI, "?" + queryParams, "");

        for(String deleteParam: uriParamsToDelete) {
            queryParams = StringUtils.replace(queryParams, deleteParam, "");
        }
        queryParams = StringUtils.replace(queryParams, "&&", "&");

        // We can sometimes be left with && in the URI
        if(!"".equals(queryParams) && queryParams.toCharArray()[0] == "&".charAt(0)) {
            queryParams = queryParams.substring(1);
        } else if("".equals(queryParams)) {
            return vfsURI;
        }

        String[] queryParamsArray = queryParams.split("&");
        StringBuilder newQueryParams = new StringBuilder("");
        if(queryParamsArray.length > 0) {
            for(String param : queryParamsArray) {
                newQueryParams.append(param);
                newQueryParams.append("&");
            }
            newQueryParams = newQueryParams.deleteCharAt(newQueryParams.length()-1);
            if(!"".equals(newQueryParams)) {
                return vfsURI + "?" + newQueryParams;
            } else {
                return vfsURI;
            }
        } else {
            return originalFileURI.substring(VFSConstants.VFS_PREFIX.length());
        }
    }

    public boolean isForceCreateFolder(MessageContext msgCtx) {
        // first preference to set on the current message context
        Map transportHeaders = (Map) msgCtx.getProperty(MessageContext.TRANSPORT_HEADERS);
        if (transportHeaders != null && "true"
                .equals((String) transportHeaders.get(VFSConstants.FORCE_CREATE_FOLDER))) {
            return true;
        }

        // next check if the OutTransportInfo specifies one
        return this.isForceCreateFolder();
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getOutFileURI() {
        return outFileURI;
    }

    public boolean getSendFileSynchronously(){
        return sendFileSynchronously;
    }

    public String getOutFileName() {
        return outFileName;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public long getReconnectTimeout() {
        return reconnectTimeout;
    }

    public void setReconnectTimeout(long reconnectTimeout) {
        this.reconnectTimeout = reconnectTimeout;
    }
    
    public boolean isAppend() {
        return append;
    }

    public void setAppend(boolean append) {
        this.append = append;
    }

    public String getContentType() {
        return contentType;
    }

    public boolean isFileLockingEnabled() {
        return fileLocking;
    }

    public Map<String, String> getOutFileSystemOptionsMap() {
        return fso;
    }

    private void setOutFileSystemOptionsMap(Map<String, String> fso) {
        HashMap<String, String> options = new HashMap<>();
        if (VFSConstants.SCHEME_SFTP.equals(fso.get(VFSConstants.SCHEME))) {
            for (String key: fso.keySet()) {
                options.put(key.replaceAll(VFSConstants.SFTP_PREFIX, ""), fso.get(key));
            }
        }

        if (VFSConstants.SCHEME_FTP.equals(fso.get(VFSConstants.SCHEME)) ||
                VFSConstants.SCHEME_FTPS.equals(fso.get(VFSConstants.SCHEME))) {
            options.putAll(fso);
            String fileType = fso.remove(VFSConstants.FILE_TYPE_PREFIX);
            if (fileType != null) {
                options.put(VFSConstants.FILE_TYPE, fileType);
            }
        }

        if (VFSConstants.SCHEME_SMB2.equals(fso.get(VFSConstants.SCHEME))) {
            options.putAll(fso);
        }
        this.fso = options;

    }

    /**
     * @return the forceCreateFolder
     */
    public boolean isForceCreateFolder() {
        return forceCreateFolder;
    }

    /**
     * @param forceCreateFolder the forceCreateFolder to set
     */
    public void setForceCreateFolder(boolean forceCreateFolder) {
        this.forceCreateFolder = forceCreateFolder;
    }

    public boolean isUpdateLastModified() {

        return updateLastModified;
    }
}
