package com.lts.job.tracker.support;

import com.lts.job.core.domain.JobResult;
import com.lts.job.core.exception.RemotingSendException;
import com.lts.job.core.protocol.JobProtos;
import com.lts.job.core.protocol.command.JobFinishedRequest;
import com.lts.job.core.remoting.RemotingServerDelegate;
import com.lts.job.remoting.InvokeCallback;
import com.lts.job.remoting.exception.RemotingCommandFieldCheckException;
import com.lts.job.remoting.netty.ResponseFuture;
import com.lts.job.remoting.protocol.RemotingCommand;
import com.lts.job.tracker.domain.JobClientNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Created by hugui on 3/2/15.
 */
public class ClientNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientNotifier.class.getSimpleName());
    private RemotingServerDelegate remotingServer;
    private ClientNotifyHandler clientNotifyHandler;

    public ClientNotifier(RemotingServerDelegate remotingServer, ClientNotifyHandler clientNotifyHandler) {
        this.remotingServer = remotingServer;
        this.clientNotifyHandler = clientNotifyHandler;
    }

    /**
     * 发送给客户端
     *
     * @param jobResults
     * @return
     */
    public void send(List<JobResult> jobResults) {
        // 单个 就不用 分组了
        if (jobResults.size() == 1) {

            JobResult jobResult = jobResults.get(0);
            if (!send0(jobResult.getJob().getNodeGroup(), jobResults)) {
                // 如果没有完成就返回
                clientNotifyHandler.handleFailed(jobResults);
            }
        } else if (jobResults.size() > 1) {

            List<JobResult> failedJobResult = new ArrayList<JobResult>();

            // 有多个要进行分组 (出现在 失败重发的时候)
            Map<String/*nodeGroup*/, List<JobResult>> groupMap = new HashMap<String, List<JobResult>>();

            for (JobResult jobResult : jobResults) {
                List<JobResult> jobResultList = groupMap.get(jobResult.getJob().getNodeGroup());
                if (jobResultList == null) {
                    jobResultList = new ArrayList<JobResult>();
                    groupMap.put(jobResult.getJob().getNodeGroup(), jobResultList);
                }
                jobResultList.add(jobResult);
            }
            for (Map.Entry<String, List<JobResult>> entry : groupMap.entrySet()) {

                if (!send0(entry.getKey(), entry.getValue())) {
                    failedJobResult.addAll(entry.getValue());
                }
            }
            clientNotifyHandler.handleFailed(failedJobResult);
        }
    }

    /**
     * 发送给客户端
     * 返回是否发送成功还是失败
     *
     * @param nodeGroup
     * @param jobResults
     * @return
     */
    private boolean send0(String nodeGroup, final List<JobResult> jobResults) {
        // 得到 可用的客户端节点
        JobClientNode jobClientNode = JobClientManager.INSTANCE.getAvailableJobClient(nodeGroup);

        if (jobClientNode == null) {
            return false;
        }

        JobFinishedRequest requestBody = new JobFinishedRequest();
        requestBody.setJobResults(jobResults);
        RemotingCommand commandRequest = RemotingCommand.createRequestCommand(JobProtos.RequestCode.JOB_FINISHED.code(), requestBody);

        final boolean[] result = new boolean[1];
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            remotingServer.invokeAsync(jobClientNode.getChannel().getChannel(), commandRequest, new InvokeCallback() {
                @Override
                public void operationComplete(ResponseFuture responseFuture) {
                    try {
                        RemotingCommand commandResponse = responseFuture.getResponseCommand();

                        if (commandResponse != null && commandResponse.getCode() == JobProtos.ResponseCode.JOB_NOTIFY_SUCCESS.code()) {
                            clientNotifyHandler.handleSuccess(jobResults);
                            result[0] = true;
                        }else{
                            result[0] = false;
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });

            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } catch (RemotingSendException e) {
            LOGGER.error("通知客户端失败!", e);
        } catch (RemotingCommandFieldCheckException e) {
            LOGGER.error("通知客户端失败!", e);
        }
        return result[0];
    }

}
