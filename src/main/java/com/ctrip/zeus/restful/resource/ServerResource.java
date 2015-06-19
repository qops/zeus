package com.ctrip.zeus.restful.resource;

import com.ctrip.zeus.auth.Authorize;
import com.ctrip.zeus.exceptions.ValidationException;
import com.ctrip.zeus.lock.DbLockFactory;
import com.ctrip.zeus.lock.DistLock;
import com.ctrip.zeus.model.entity.*;
import com.ctrip.zeus.service.build.BuildInfoService;
import com.ctrip.zeus.service.build.BuildService;
import com.ctrip.zeus.service.build.NginxConfService;
import com.ctrip.zeus.service.model.GroupRepository;
import com.ctrip.zeus.service.model.SlbRepository;
import com.ctrip.zeus.service.nginx.NginxService;
import com.ctrip.zeus.service.status.GroupStatusService;
import com.ctrip.zeus.service.status.StatusService;
import com.ctrip.zeus.util.AssertUtils;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author:xingchaowang
 * @date: 3/15/2015.
 */
@Component
@Path("/op")
public class ServerResource {

    @Resource
    StatusService statusService;
    @Resource
    private BuildService buildService;
    @Resource
    private BuildInfoService buildInfoService;
    @Resource
    private NginxService nginxAgentService;
    @Resource
    private NginxService nginxService;
    @Resource
    private GroupStatusService groupStatusService;
    @Resource
    private GroupRepository groupRepository;
    @Resource
    private NginxConfService nginxConfService;
    @Resource
    private DbLockFactory dbLockFactory;
    @Resource
    private SlbRepository slbRepository;


    private static DynamicIntProperty lockTimeout = DynamicPropertyFactory.getInstance().getIntProperty("lock.timeout", 5000);


    @GET
    @Path("/upServer")
    @Authorize(name="upDownServer")
    public Response upServer(@Context HttpServletRequest request,@Context HttpHeaders hh, @QueryParam("ip") String ip) throws Exception{
        //update status
        statusService.upServer(ip);
        return serverOps(hh,ip);
    }

    @GET
    @Path("/downServer")
    @Authorize(name="upDownServer")
    public Response downServer(@Context HttpServletRequest request,@Context HttpHeaders hh, @QueryParam("ip") String ip) throws Exception{
        //update status
        statusService.downServer(ip);
        return serverOps(hh, ip);
    }

    private Response serverOps(HttpHeaders hh , String serverip)throws Exception{
        //get slb by serverip
        List<Slb> slblist = slbRepository.listByGroupServerAndGroup(serverip,null);
        AssertUtils.assertNotNull(slblist, "[UpServer/DownServer] Can not find slb by server ip :[" + serverip + "],Please check the configuration and server ip!");

        for (Slb slb : slblist)
        {
            Long slbId = slb.getId();
            int ticket = buildInfoService.getTicket(slbId);

            boolean buildFlag = false;
            DistLock buildLock = dbLockFactory.newLock(slbId + "_build");
            try{
                buildLock.lock(lockTimeout.get());
                buildFlag =buildService.build(slbId,ticket);
            }finally {
                buildLock.unlock();
            }
            if (buildFlag) {
                DistLock writeLock = dbLockFactory.newLock(slbId + "_writeAndReload");
                try {
                    writeLock.lock(lockTimeout.get());
                    //Push Service
                    nginxAgentService.writeAllAndLoadAll(slbId);
                } finally {
                    writeLock.unlock();
                }
            }

        }

        ServerStatus ss = new ServerStatus().setIp(serverip).setUp(statusService.getServerStatus(serverip));
        List<String> applist = groupRepository.listGroupsByGroupServer(serverip);

        if (applist!=null)
        {
            for (String name : applist)
            {
                ss.addGroupName(name);
            }
        }

        if (MediaType.APPLICATION_XML_TYPE.equals(hh.getMediaType())) {
            return Response.status(200).entity(String.format(ServerStatus.XML, ss)).type(MediaType.APPLICATION_XML).build();
        } else {
            return Response.status(200).entity(String.format(ServerStatus.JSON, ss)).type(MediaType.APPLICATION_JSON).build();
        }
    }

    @GET
    @Path("/upMember")
    @Authorize(name="upDownMember")
    public Response upMember(@Context HttpServletRequest request,
                             @Context HttpHeaders hh,
                             @QueryParam("groupId") Long groupId,
                             @QueryParam("groupName") String groupName,
                             @QueryParam("ip") List<String> ips,
                             @QueryParam("batch") Boolean batch)throws Exception
    {
        Long _groupId = null;
        List<String> _ips = new ArrayList<>();
        if (groupId != null)
        {
            _groupId = groupId;
        }else if (groupName != null){
            _groupId = groupRepository.get(groupName).getId();
        }
        if (null == _groupId)
        {
            throw new ValidationException("Group Id or Name not found!");
        }
        if (null != batch && batch.equals(true))
        {
            Group gp = groupRepository.getById(_groupId);
            List<GroupServer> servers = gp.getGroupServers();
            for (GroupServer gs : servers)
            {
                _ips.add(gs.getIp());
            }
        }else if (ips != null)
        {
            _ips.addAll(ips);
        }
        statusService.upMember(_groupId,_ips);
        return memberOps(hh, _groupId, _ips);
    }

    @GET
    @Path("/downMember")
    @Authorize(name="upDownMember")
    public Response downMember(@Context HttpServletRequest request,
                               @Context HttpHeaders hh,
                               @QueryParam("groupId") Long groupId,
                               @QueryParam("groupName") String groupName,
                               @QueryParam("ip") List<String> ips,
                               @QueryParam("batch") Boolean batch)throws Exception
    {
        Long _groupId = null;
        List<String> _ips = new ArrayList<>();

        if (groupId != null)
        {
            _groupId = groupId;
        }else if (groupName != null){
            _groupId = groupRepository.get(groupName).getId();
        }
        if (null == _groupId)
        {
            throw new ValidationException("Group Id or Name not found!");
        }
        if (null != batch && batch.equals(true))
        {
            Group gp = groupRepository.getById(_groupId);
            List<GroupServer> servers = gp.getGroupServers();
            for (GroupServer gs : servers)
            {
                _ips.add(gs.getIp());
            }
        }else if (ips != null)
        {
            _ips.addAll(ips);
        }
        statusService.downMember(_groupId, _ips);
        return memberOps(hh, _groupId, _ips);
    }


    private Response memberOps(HttpHeaders hh,Long groupId,List<String> ips)throws Exception{

        //get slb by groupId and ip
        Set<Slb> slbList = new HashSet<>();
        List<Slb> tmp ;
        for (String ip : ips)
        {
            tmp = slbRepository.listByGroupServerAndGroup(ip,groupId);
            AssertUtils.assertNotNull(tmp,"Not find slb for GroupId ["+groupId+"] and ip ["+ip+"]");
            slbList.addAll(tmp);
        }
        AssertUtils.assertNotEquals(0,slbList.size(),"Group or ips is not correct!");

        for (Slb slb : slbList) {
            Long slbId = slb.getId();
            //get ticket
            int ticket = buildInfoService.getTicket(slbId);

            boolean buildFlag = false;
            boolean dyopsFlag = false;
            List<DyUpstreamOpsData> dyUpstreamOpsDataList = null;
            DistLock buildLock = dbLockFactory.newLock("build_"+slbId);
            try{
                buildLock.lock(lockTimeout.get());
                buildFlag =buildService.build(slbId,ticket);
            }finally {
                buildLock.unlock();
            }
            if (buildFlag) {
                DistLock writeLock = dbLockFactory.newLock("writeAndReload_" + slbId);
                try {
                    writeLock.lock(lockTimeout.get());
                    //push
                    dyopsFlag=nginxAgentService.writeALLToDisk(slbId);
                    if (!dyopsFlag)
                    {
                        throw new Exception("write all to disk failed!");
                    }
                } finally {
                    writeLock.unlock();
                }
            }
            if (dyopsFlag){
                DistLock dyopsLock = dbLockFactory.newLock(slbId + "_" + groupId + "_dyops");
                try{
                    dyopsLock.lock(lockTimeout.get());
                    dyUpstreamOpsDataList = nginxConfService.buildUpstream(slb, groupId);
                    nginxAgentService.dyops(slbId, dyUpstreamOpsDataList);
                }finally {
                    dyopsLock.unlock();
                }
            }
        }

        List<GroupStatus> statuses = groupStatusService.getGroupStatus(groupId);
        //ToDo set group name and slb name
        GroupStatus groupStatusList = new GroupStatus().setGroupId(groupId).setSlbName("");
        for (GroupStatus groupStatus : statuses)
        {
            groupStatusList.setSlbName(groupStatusList.getSlbName() + " " + groupStatus.getSlbName())
                            .setGroupName(groupStatus.getGroupName())
                            .setSlbId(groupStatus.getSlbId());
            for(GroupServerStatus b : groupStatus.getGroupServerStatuses())
            {
                groupStatusList.addGroupServerStatus(b);
            }
        }

        if (MediaType.APPLICATION_XML_TYPE.equals(hh.getMediaType())) {
            return Response.status(200).entity(String.format(GroupStatus.XML, groupStatusList)).type(MediaType.APPLICATION_XML).build();
        } else {
            return Response.status(200).entity(String.format(GroupStatus.JSON, groupStatusList)).type(MediaType.APPLICATION_JSON).build();
        }
    }

}

