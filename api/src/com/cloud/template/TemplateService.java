// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.template;

import java.net.URISyntaxException;
import java.util.List;

import org.apache.cloudstack.api.user.iso.command.RegisterIsoCmd;
import org.apache.cloudstack.api.user.template.command.CopyTemplateCmd;
import org.apache.cloudstack.api.user.iso.command.DeleteIsoCmd;
import org.apache.cloudstack.api.user.template.command.DeleteTemplateCmd;
import org.apache.cloudstack.api.user.iso.command.ExtractIsoCmd;
import org.apache.cloudstack.api.user.template.command.ExtractTemplateCmd;
import com.cloud.api.commands.ListTemplateOrIsoPermissionsCmd;
import org.apache.cloudstack.api.user.template.command.RegisterTemplateCmd;
import com.cloud.api.commands.UpdateTemplateOrIsoPermissionsCmd;
import com.cloud.exception.InternalErrorException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.StorageUnavailableException;

public interface TemplateService {

    VirtualMachineTemplate registerTemplate(RegisterTemplateCmd cmd) throws URISyntaxException, ResourceAllocationException;

    VirtualMachineTemplate registerIso(RegisterIsoCmd cmd) throws IllegalArgumentException, ResourceAllocationException;

    VirtualMachineTemplate copyTemplate(CopyTemplateCmd cmd) throws StorageUnavailableException, ResourceAllocationException;

    VirtualMachineTemplate prepareTemplate(long templateId, long zoneId);

    boolean detachIso(long vmId);

    boolean attachIso(long isoId, long vmId);

    /**
     * Deletes a template
     *
     * @param cmd
     *            - the command specifying templateId
     */
    boolean deleteTemplate(DeleteTemplateCmd cmd);

    /**
     * Deletes a template
     *
     * @param cmd
     *            - the command specifying isoId
     * @return true if deletion is successful, false otherwise
     */
    boolean deleteIso(DeleteIsoCmd cmd);

    /**
     * Extracts an ISO
     *
     * @param cmd
     *            - the command specifying the mode and id of the ISO
     * @return extractId.
     */
    Long extract(ExtractIsoCmd cmd) throws InternalErrorException;

    /**
     * Extracts a Template
     *
     * @param cmd
     *            - the command specifying the mode and id of the template
     * @return extractId
     */
    Long extract(ExtractTemplateCmd cmd) throws InternalErrorException;

    VirtualMachineTemplate getTemplate(long templateId);

    List<String> listTemplatePermissions(ListTemplateOrIsoPermissionsCmd cmd);

    boolean updateTemplateOrIsoPermissions(UpdateTemplateOrIsoPermissionsCmd cmd);
}
