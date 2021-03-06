/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.rest.controller;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.JsonUtil;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.cube.model.CubeBuildTypeEnum;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.job.JobInstance;
import org.apache.kylin.job.JoinedFlatTable;
import org.apache.kylin.job.exception.JobException;
import org.apache.kylin.job.hadoop.hive.CubeJoinedFlatTableDesc;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.metadata.model.DataModelDesc;
import org.apache.kylin.metadata.model.SegmentStatusEnum;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.rest.exception.BadRequestException;
import org.apache.kylin.rest.exception.ForbiddenException;
import org.apache.kylin.rest.exception.InternalErrorException;
import org.apache.kylin.rest.exception.NotFoundException;
import org.apache.kylin.rest.request.CubeRequest;
import org.apache.kylin.rest.request.JobBuildRequest;
import org.apache.kylin.rest.response.GeneralResponse;
import org.apache.kylin.rest.response.HBaseResponse;
import org.apache.kylin.rest.service.CubeService;
import org.apache.kylin.rest.service.JobService;
import org.apache.kylin.storage.hbase.coprocessor.observer.ObserverEnabler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * CubeController is defined as Restful API entrance for UI.
 *
 * @author jianliu
 */
@Controller
@RequestMapping(value = "/cubes")
public class CubeController extends BasicController {
    private static final Logger logger = LoggerFactory.getLogger(CubeController.class);

    @Autowired
    private CubeService cubeService;

    @Autowired
    private JobService jobService;

    @RequestMapping(value = "", method = { RequestMethod.GET })
    @ResponseBody
    public List<CubeInstance> getCubes(@RequestParam(value = "cubeName", required = false) String cubeName, @RequestParam(value = "projectName", required = false) String projectName, @RequestParam("limit") Integer limit, @RequestParam("offset") Integer offset) {
        return cubeService.getCubes(cubeName, projectName, (null == limit) ? 20 : limit, offset);
    }

    /**
     * Get hive SQL of the cube
     *
     * @param cubeName Cube Name
     * @return
     * @throws UnknownHostException
     * @throws IOException
     */
    @RequestMapping(value = "/{cubeName}/segs/{segmentName}/sql", method = { RequestMethod.GET })
    @ResponseBody
    public GeneralResponse getSql(@PathVariable String cubeName, @PathVariable String segmentName) {
        CubeInstance cube = cubeService.getCubeManager().getCube(cubeName);
        CubeDesc cubeDesc = cube.getDescriptor();
        CubeSegment cubeSegment = cube.getSegment(segmentName, SegmentStatusEnum.READY);
        CubeJoinedFlatTableDesc flatTableDesc = new CubeJoinedFlatTableDesc(cubeDesc, cubeSegment);
        String sql = JoinedFlatTable.generateSelectDataStatement(flatTableDesc);

        GeneralResponse repsonse = new GeneralResponse();
        repsonse.setProperty("sql", sql);

        return repsonse;
    }

    /**
     * Update cube notify list
     *
     * @param cubeName
     * @param notifyList
     * @throws IOException
     * @throws CubeIntegrityException
     */
    @RequestMapping(value = "/{cubeName}/notify_list", method = { RequestMethod.PUT })
    @ResponseBody
    public void updateNotifyList(@PathVariable String cubeName, @RequestBody List<String> notifyList) {
        CubeInstance cube = cubeService.getCubeManager().getCube(cubeName);

        if (cube == null) {
            throw new InternalErrorException("Cannot find cube " + cubeName);
        }

        try {
            cubeService.updateCubeNotifyList(cube, notifyList);
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalErrorException(e.getLocalizedMessage());
        }

    }

    @RequestMapping(value = "/{cubeName}/cost", method = { RequestMethod.PUT })
    @ResponseBody
    public CubeInstance updateCubeCost(@PathVariable String cubeName, @RequestParam(value = "cost") int cost) {
        try {
            return cubeService.updateCubeCost(cubeName, cost);
        } catch (Exception e) {
            String message = "Failed to update cube cost: " + cubeName + " : " + cost;
            logger.error(message, e);
            throw new InternalErrorException(message + " Caused by: " + e.getMessage(), e);
        }
    }

    @RequestMapping(value = "/{cubeName}/coprocessor", method = { RequestMethod.PUT })
    @ResponseBody
    public Map<String, Boolean> updateCubeCoprocessor(@PathVariable String cubeName, @RequestParam(value = "force") String force) {
        try {
            ObserverEnabler.updateCubeOverride(cubeName, force);
            return ObserverEnabler.getCubeOverrides();
        } catch (Exception e) {
            String message = "Failed to update cube coprocessor: " + cubeName + " : " + force;
            logger.error(message, e);
            throw new InternalErrorException(message + " Caused by: " + e.getMessage(), e);
        }
    }

    /**
     * Force rebuild a cube's lookup table snapshot
     *
     * @throws IOException
     */
    @RequestMapping(value = "/{cubeName}/segs/{segmentName}/refresh_lookup", method = { RequestMethod.PUT })
    @ResponseBody
    public CubeInstance rebuildLookupSnapshot(@PathVariable String cubeName, @PathVariable String segmentName, @RequestParam(value = "lookupTable") String lookupTable) {
        try {
            return cubeService.rebuildLookupSnapshot(cubeName, segmentName, lookupTable);
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalErrorException(e.getLocalizedMessage());
        }
    }

    /**
     * Send a rebuild cube job
     *
     * @param cubeName Cube ID
     * @return
     * @throws IOException
     */
    @RequestMapping(value = "/{cubeName}/rebuild", method = { RequestMethod.PUT })
    @ResponseBody
    public JobInstance rebuild(@PathVariable String cubeName, @RequestBody JobBuildRequest jobBuildRequest) {
        try {
            String submitter = SecurityContextHolder.getContext().getAuthentication().getName();
            CubeInstance cube = jobService.getCubeManager().getCube(cubeName);
            return jobService.submitJob(cube, jobBuildRequest.getStartTime(), jobBuildRequest.getEndTime(), //
                    CubeBuildTypeEnum.valueOf(jobBuildRequest.getBuildType()), jobBuildRequest.isForceMergeEmptySegment(), submitter);
        } catch (JobException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalErrorException(e.getLocalizedMessage());
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalErrorException(e.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/{cubeName}/disable", method = { RequestMethod.PUT })
    @ResponseBody
    public CubeInstance disableCube(@PathVariable String cubeName) {
        try {
            CubeInstance cube = cubeService.getCubeManager().getCube(cubeName);

            if (cube == null) {
                throw new InternalErrorException("Cannot find cube " + cubeName);
            }

            return cubeService.disableCube(cube);
        } catch (Exception e) {
            String message = "Failed to disable cube: " + cubeName;
            logger.error(message, e);
            throw new InternalErrorException(message + " Caused by: " + e.getMessage(), e);
        }
    }

    @RequestMapping(value = "/{cubeName}/purge", method = { RequestMethod.PUT })
    @ResponseBody
    public CubeInstance purgeCube(@PathVariable String cubeName) {
        try {
            CubeInstance cube = cubeService.getCubeManager().getCube(cubeName);

            if (cube == null) {
                throw new InternalErrorException("Cannot find cube " + cubeName);
            }

            return cubeService.purgeCube(cube);
        } catch (Exception e) {
            String message = "Failed to purge cube: " + cubeName;
            logger.error(message, e);
            throw new InternalErrorException(message + " Caused by: " + e.getMessage(), e);
        }
    }

    @RequestMapping(value = "/{cubeName}/enable", method = { RequestMethod.PUT })
    @ResponseBody
    public CubeInstance enableCube(@PathVariable String cubeName) {
        try {
            CubeInstance cube = cubeService.getCubeManager().getCube(cubeName);
            if (null == cube) {
                throw new InternalErrorException("Cannot find cube " + cubeName);
            }

            return cubeService.enableCube(cube);
        } catch (Exception e) {
            String message = "Failed to enable cube: " + cubeName;
            logger.error(message, e);
            throw new InternalErrorException(message + " Caused by: " + e.getMessage(), e);
        }
    }

    @RequestMapping(value = "/{cubeName}", method = { RequestMethod.DELETE })
    @ResponseBody
    public void deleteCube(@PathVariable String cubeName) {
        CubeInstance cube = cubeService.getCubeManager().getCube(cubeName);
        if (null == cube) {
            throw new NotFoundException("Cube with name " + cubeName + " not found..");
        }

        try {
            cubeService.deleteCube(cube);
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalErrorException("Failed to delete cube. " + " Caused by: " + e.getMessage(), e);
        }
    }

    /**
     * Get available table list of the input database
     *
     * @return Table metadata array
     * @throws IOException
     */
    @RequestMapping(value = "", method = { RequestMethod.POST })
    @ResponseBody
    public CubeRequest saveCubeDesc(@RequestBody CubeRequest cubeRequest) {
        //Update Model
        MetadataManager metaManager = MetadataManager.getInstance(KylinConfig.getInstanceFromEnv());
        DataModelDesc modelDesc = deserializeDataModelDesc(cubeRequest);
        if (modelDesc == null || StringUtils.isEmpty(modelDesc.getName())) {
            return cubeRequest;
        }

        try {
            DataModelDesc existingModel = metaManager.getDataModelDesc(modelDesc.getName());
            if (existingModel == null) {
                metaManager.createDataModelDesc(modelDesc);
            } else {
                modelDesc.setLastModified(existingModel.getLastModified());
                metaManager.updateDataModelDesc(modelDesc);
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            logger.error("Failed to deal with the request:" + e.getLocalizedMessage(), e);
            throw new InternalErrorException("Failed to deal with the request: " + e.getLocalizedMessage());
        }

        CubeDesc desc = deserializeCubeDesc(cubeRequest);
        if (desc == null) {
            return cubeRequest;
        }

        String name = CubeService.getCubeNameFromDesc(desc.getName());
        if (StringUtils.isEmpty(name)) {
            logger.info("Cube name should not be empty.");
            throw new BadRequestException("Cube name should not be empty.");
        }

        try {
            desc.setUuid(UUID.randomUUID().toString());
            String projectName = (null == cubeRequest.getProject()) ? ProjectInstance.DEFAULT_PROJECT_NAME : cubeRequest.getProject();
            cubeService.createCubeAndDesc(name, projectName, desc);
        } catch (Exception e) {
            logger.error("Failed to deal with the request.", e);
            throw new InternalErrorException(e.getLocalizedMessage(), e);
        }

        cubeRequest.setUuid(desc.getUuid());
        cubeRequest.setSuccessful(true);
        return cubeRequest;
    }

    /**
     * Update cube description. If cube signature has changed, all existing cube segments are dropped.
     *
     * @return Table metadata array
     * @throws JsonProcessingException
     */
    @RequestMapping(value = "", method = { RequestMethod.PUT })
    @ResponseBody
    public CubeRequest updateCubeDesc(@RequestBody CubeRequest cubeRequest) throws JsonProcessingException {
        CubeDesc desc = deserializeCubeDesc(cubeRequest);
        if (desc == null) {
            return cubeRequest;
        }

        final String cubeName = desc.getName();
        if (StringUtils.isEmpty(cubeName)) {
            return errorRequest(cubeRequest, "Missing cubeName");
        }

        MetadataManager metadataManager = MetadataManager.getInstance(KylinConfig.getInstanceFromEnv());
        // KYLIN-958: disallow data model change
        if (StringUtils.isNotEmpty(cubeRequest.getModelDescData())) {
            DataModelDesc modelDesc = deserializeDataModelDesc(cubeRequest);
            if (modelDesc == null) {
                return cubeRequest;
            }

            final String modeName = modelDesc.getName();

            if (!StringUtils.equals(desc.getModelName(), modeName)) {
                return errorRequest(cubeRequest, "CubeDesc.model_name " + desc.getModelName() + " not consistent with model " + modeName);
            }

            DataModelDesc oldModelDesc = metadataManager.getDataModelDesc(modeName);
            if (oldModelDesc == null) {
                return errorRequest(cubeRequest, "Data model " + modeName + " not found");
            }

            if (!modelDesc.compatibleWith(oldModelDesc)) {
                return errorRequest(cubeRequest, "Update data model is not allowed! Please create a new cube if needed");
            }

        }

        // Check if the cube is editable
        if (!cubeService.isCubeDescEditable(desc)) {
            String error = "Cube desc " + desc.getName().toUpperCase() + " is not editable.";
            return errorRequest(cubeRequest, error);
        }

        try {
            CubeInstance cube = cubeService.getCubeManager().getCube(cubeName);
            String projectName = (null == cubeRequest.getProject()) ? ProjectInstance.DEFAULT_PROJECT_NAME : cubeRequest.getProject();
            desc = cubeService.updateCubeAndDesc(cube, desc, projectName);

        } catch (AccessDeniedException accessDeniedException) {
            throw new ForbiddenException("You don't have right to update this cube.");
        } catch (Exception e) {
            logger.error("Failed to deal with the request:" + e.getLocalizedMessage(), e);
            throw new InternalErrorException("Failed to deal with the request: " + e.getLocalizedMessage());
        }

        if (desc.getError().isEmpty()) {
            cubeRequest.setSuccessful(true);
        } else {
            logger.warn("Cube " + desc.getName() + " fail to create because " + desc.getError());
            errorRequest(cubeRequest, omitMessage(desc.getError()));
        }
        String descData = JsonUtil.writeValueAsIndentString(desc);
        cubeRequest.setCubeDescData(descData);

        return cubeRequest;
    }

    /**
     * Get available table list of the input database
     *
     * @return true
     * @throws IOException
     */
    @RequestMapping(value = "/{cubeName}/hbase", method = { RequestMethod.GET })
    @ResponseBody
    public List<HBaseResponse> getHBaseInfo(@PathVariable String cubeName) {
        List<HBaseResponse> hbase = new ArrayList<HBaseResponse>();

        CubeInstance cube = cubeService.getCubeManager().getCube(cubeName);
        if (null == cube) {
            throw new InternalErrorException("Cannot find cube " + cubeName);
        }

        List<CubeSegment> segments = cube.getSegments();

        for (CubeSegment segment : segments) {
            String tableName = segment.getStorageLocationIdentifier();
            HBaseResponse hr = null;

            // Get info of given table.
            try {
                hr = cubeService.getHTableInfo(tableName);
            } catch (IOException e) {
                logger.error("Failed to calcuate size of HTable \"" + tableName + "\".", e);
            }

            if (null == hr) {
                logger.info("Failed to calcuate size of HTable \"" + tableName + "\".");
                hr = new HBaseResponse();
            }

            hr.setTableName(tableName);
            hr.setDateRangeStart(segment.getDateRangeStart());
            hr.setDateRangeEnd(segment.getDateRangeEnd());
            hbase.add(hr);
        }

        return hbase;
    }

    private CubeDesc deserializeCubeDesc(CubeRequest cubeRequest) {
        CubeDesc desc = null;
        try {
            logger.debug("Deserialize cube desc " + cubeRequest.getCubeDescData());
            desc = JsonUtil.readValue(cubeRequest.getCubeDescData(), CubeDesc.class);
            //            desc.setRetentionRange(cubeRequest.getRetentionRange());
        } catch (JsonParseException e) {
            logger.error("The cube definition is not valid.", e);
            errorRequest(cubeRequest, e.getMessage());
        } catch (JsonMappingException e) {
            logger.error("The cube definition is not valid.", e);
            errorRequest(cubeRequest, e.getMessage());
        } catch (IOException e) {
            logger.error("Failed to deal with the request.", e);
            throw new InternalErrorException("Failed to deal with the request:" + e.getMessage(), e);
        }
        return desc;
    }

    private DataModelDesc deserializeDataModelDesc(CubeRequest cubeRequest) {
        DataModelDesc desc = null;
        try {
            logger.debug("Deserialize data model " + cubeRequest.getModelDescData());
            desc = JsonUtil.readValue(cubeRequest.getModelDescData(), DataModelDesc.class);
        } catch (JsonParseException e) {
            logger.error("The data model definition is not valid.", e);
            errorRequest(cubeRequest, e.getMessage());
        } catch (JsonMappingException e) {
            logger.error("The data model definition is not valid.", e);
            errorRequest(cubeRequest, e.getMessage());
        } catch (IOException e) {
            logger.error("Failed to deal with the request.", e);
            throw new InternalErrorException("Failed to deal with the request:" + e.getMessage(), e);
        }
        return desc;
    }

    /**
     * @return
     */
    private String omitMessage(List<String> errors) {
        StringBuffer buffer = new StringBuffer();
        for (Iterator<String> iterator = errors.iterator(); iterator.hasNext();) {
            String string = (String) iterator.next();
            buffer.append(string);
            buffer.append("\n");
        }
        return buffer.toString();
    }

    private CubeRequest errorRequest(CubeRequest request, String errmsg) {
        request.setCubeDescData("");
        request.setSuccessful(false);
        request.setMessage(errmsg);
        return request;
    }

    public void setCubeService(CubeService cubeService) {
        this.cubeService = cubeService;
    }

    public void setJobService(JobService jobService) {
        this.jobService = jobService;
    }

}
