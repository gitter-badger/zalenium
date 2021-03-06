package de.zalando.tip.zalenium.proxy;

import com.google.common.annotations.VisibleForTesting;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.Container;
import com.spotify.docker.client.messages.ExecCreation;
import de.zalando.tip.zalenium.util.CommonProxyUtilities;
import de.zalando.tip.zalenium.util.Environment;
import de.zalando.tip.zalenium.util.GoogleAnalyticsApi;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.RemoteNotReachableException;
import org.openqa.grid.common.exception.RemoteUnregisterException;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;
import org.openqa.grid.web.servlet.handler.RequestType;
import org.openqa.grid.web.servlet.handler.WebDriverRequest;
import org.openqa.selenium.remote.CapabilityType;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
    The implementation of this class was inspired on https://gist.github.com/krmahadevan/4649607
 */
@SuppressWarnings("WeakerAccess")
public class DockerSeleniumRemoteProxy extends DefaultRemoteProxy {

    @VisibleForTesting
    static final String ZALENIUM_VIDEO_RECORDING_ENABLED = "ZALENIUM_VIDEO_RECORDING_ENABLED";
    @VisibleForTesting
    static final boolean DEFAULT_VIDEO_RECORDING_ENABLED = true;
    private static final Logger LOGGER = Logger.getLogger(DockerSeleniumRemoteProxy.class.getName());
    // Amount of tests that can be executed in the node
    private static final int MAX_UNIQUE_TEST_SESSIONS = 1;
    private static final DockerClient defaultDockerClient = new DefaultDockerClient("unix:///var/run/docker.sock");
    private static final Environment defaultEnvironment = new Environment();
    private static boolean videoRecordingEnabled;
    private static DockerClient dockerClient = defaultDockerClient;
    private static Environment env = defaultEnvironment;
    private static CommonProxyUtilities commonProxyUtilities = new CommonProxyUtilities();
    private int amountOfExecutedTests;
    private long executionTime = 0;
    private String testName;
    private String testGroup;
    private String browserName;
    private boolean stopSessionRequestReceived = false;
    private DockerSeleniumNodePoller dockerSeleniumNodePollerThread = null;
    private GoogleAnalyticsApi ga = new GoogleAnalyticsApi();

    public DockerSeleniumRemoteProxy(RegistrationRequest request, Registry registry) {
        super(request, registry);
        this.amountOfExecutedTests = 0;
        readEnvVarForVideoRecording();
    }

    @VisibleForTesting
    static void readEnvVarForVideoRecording() {
        boolean videoEnabled = env.getBooleanEnvVariable(ZALENIUM_VIDEO_RECORDING_ENABLED,
                DEFAULT_VIDEO_RECORDING_ENABLED);
        setVideoRecordingEnabled(videoEnabled);
    }

    @VisibleForTesting
    static void setDockerClient(final DockerClient client) {
        dockerClient = client;
    }

    @VisibleForTesting
    static void restoreDockerClient() {
        dockerClient = defaultDockerClient;
    }

    @VisibleForTesting
    protected static void setEnv(final Environment env) {
        DockerSeleniumRemoteProxy.env = env;
    }

    @VisibleForTesting
    static void restoreEnvironment() {
        env = defaultEnvironment;
    }

    @VisibleForTesting
    protected static boolean isVideoRecordingEnabled() {
        return videoRecordingEnabled;
    }

    private static void setVideoRecordingEnabled(boolean videoRecordingEnabled) {
        DockerSeleniumRemoteProxy.videoRecordingEnabled = videoRecordingEnabled;
    }

    /*
        Incrementing the number of tests that will be executed when the session is assigned.
     */
    @Override
    public TestSession getNewSession(Map<String, Object> requestedCapability) {
        /*
            Validate first if the capability is matched
         */
        if (!hasCapability(requestedCapability)) {
            return null;
        }
        if (increaseCounter()) {
            TestSession newSession = super.getNewSession(requestedCapability);
            browserName = requestedCapability.getOrDefault(CapabilityType.BROWSER_NAME, "").toString();
            testName = requestedCapability.getOrDefault("name", "").toString();
            if (testName.isEmpty()) {
                testName = newSession.getExternalKey() != null ?
                        newSession.getExternalKey().getKey() :
                        newSession.getInternalKey();
            }
            testGroup = requestedCapability.getOrDefault("group", "").toString();
            videoRecording(VideoRecordingAction.START_RECORDING);
            return newSession;
        }
        LOGGER.log(Level.FINE, "{0} No more sessions allowed", getNodeIpAndPort());
        return null;
    }

    @Override
    public void afterCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        if (request instanceof WebDriverRequest && "DELETE".equalsIgnoreCase(request.getMethod())) {
            WebDriverRequest seleniumRequest = (WebDriverRequest) request;
            if (RequestType.STOP_SESSION.equals(seleniumRequest.getRequestType())) {
                this.stopSessionRequestReceived = true;
                String message = String.format("%s STOP_SESSION command received. Node should shutdown soon...",
                        getNodeIpAndPort());
                LOGGER.log(Level.INFO, message);
                executionTime = (System.currentTimeMillis() - session.getSlot().getLastSessionStart()) / 1000;
                ga.testEvent(DockerSeleniumRemoteProxy.class.getName(), session.getRequestedCapabilities().toString(),
                        executionTime);
            }
        }
        super.afterCommand(session, request, response);
    }

    @Override
    public void startPolling() {
        super.startPolling();
        dockerSeleniumNodePollerThread = new DockerSeleniumNodePoller(this);
        dockerSeleniumNodePollerThread.start();
    }

    @Override
    public void stopPolling() {
        super.stopPolling();
        dockerSeleniumNodePollerThread.interrupt();
    }

    @Override
    public void teardown() {
        super.teardown();
        stopPolling();
    }

    private String getNodeIpAndPort() {
        return getRemoteHost().getHost() + ":" + getRemoteHost().getPort();
    }

    /*
        Incrementing variable to count the number of tests executed, if possible.
     */
    private synchronized boolean increaseCounter() {
        // Meaning that we have already executed the allowed number of tests.
        if (isTestSessionLimitReached()) {
            return false;
        }
        amountOfExecutedTests++;
        return true;
    }

    /*
        Method to decide if the node can be removed based on the amount of executed tests.
     */
    @VisibleForTesting
    protected synchronized boolean isTestSessionLimitReached() {
        return getAmountOfExecutedTests() >= MAX_UNIQUE_TEST_SESSIONS;
    }

    @VisibleForTesting
    protected int getAmountOfExecutedTests() {
        return amountOfExecutedTests;
    }

    @VisibleForTesting
    protected void videoRecording(final VideoRecordingAction action) {
        if (isVideoRecordingEnabled()) {
            try {
                String containerId = getContainerId();
                processVideoAction(action, containerId);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, getNodeIpAndPort() + e.toString(), e);
                ga.trackException(e);
            }
        } else {
            String message = String.format("%s %s: Video recording is disabled", getNodeIpAndPort(),
                    action.getRecordingAction());
            LOGGER.log(Level.INFO, message);
        }
    }

    public String getTestName() {
        return testName == null ? "" : testName;
    }

    public String getTestGroup() {
        return testGroup == null ? "" : testGroup;
    }

    protected String getContainerId() throws DockerException, InterruptedException {
        List<Container> containerList = dockerClient.listContainers(DockerClient.ListContainersParam.allContainers());
        for (Container container : containerList) {
            String containerName = String.format("/%s_%s", DockerSeleniumStarterRemoteProxy.getContainerName(),
                    getRemoteHost().getPort());
            if (containerName.equalsIgnoreCase(container.names().get(0))) {
                return container.id();
            }
        }
        return null;
    }

    @VisibleForTesting
    void processVideoAction(final VideoRecordingAction action, final String containerId) throws
            DockerException, InterruptedException, IOException, URISyntaxException {
        final String[] command = {"bash", "-c", action.getRecordingAction()};
        final ExecCreation execCreation = dockerClient.execCreate(containerId, command,
                DockerClient.ExecCreateParam.attachStdout(), DockerClient.ExecCreateParam.attachStderr());
        final LogStream output = dockerClient.execStart(execCreation.id());
        LOGGER.log(Level.INFO, () -> String.format("%s %s", getNodeIpAndPort(), action.getRecordingAction()));
        try {
            LOGGER.log(Level.INFO, () -> String.format("%s %s", getNodeIpAndPort(), output.readFully()));
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, getNodeIpAndPort() + " " + e.toString(), e);
            ga.trackException(e);
        }

        if (VideoRecordingAction.STOP_RECORDING == action) {
            copyVideos(containerId);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @VisibleForTesting
    void copyVideos(final String containerId) throws IOException, DockerException, InterruptedException, URISyntaxException {
        String localPath = commonProxyUtilities.currentLocalPath();
        try (TarArchiveInputStream tarStream = new TarArchiveInputStream(dockerClient.archiveContainer(containerId,
                "/videos/"))) {
            TarArchiveEntry entry;
            while ((entry = tarStream.getNextTarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String fileExtension = entry.getName().substring(entry.getName().lastIndexOf('.'));
                String folderName = entry.getName().substring(0, entry.getName().indexOf('/') + 1);
                String fileName = String.format("%s_%s", entry.getName(), commonProxyUtilities.getCurrentDateAndTimeFormatted());
                fileName = fileName.replace(fileExtension, "").concat(fileExtension);
                fileName = getTestName().isEmpty() ? fileName : fileName.replace("vid_", getTestName() + "_");
                fileName = fileName.replace(' ', '_');
                fileName = fileName.replace(folderName, "");
                fileName = folderName + DockerSeleniumStarterRemoteProxy.getContainerName() + "_" + fileName;
                File curFile = new File(localPath, fileName);
                File parent = curFile.getParentFile();
                if (!parent.exists()) {
                    parent.mkdirs();
                }
                OutputStream outputStream = new FileOutputStream(curFile);
                IOUtils.copy(tarStream, outputStream);
                outputStream.close();
                commonProxyUtilities.updateDashboard(testName, executionTime, "Zalenium",
                        browserName, "Linux", fileName.replace("videos/", ""), localPath + "/videos");
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, getNodeIpAndPort() + " Something happened while copying the video file, " +
                    "most of the time it is an issue while closing the input/output stream, which is usually OK.", e);
        }
        LOGGER.log(Level.INFO, "{0} Video files copies to: {1}", new Object[]{getNodeIpAndPort(), localPath});
    }

    public enum VideoRecordingAction {
        START_RECORDING("start-video"), STOP_RECORDING("stop-video");

        private String recordingAction;

        VideoRecordingAction(String action) {
            recordingAction = action;
        }

        public String getRecordingAction() {
            return recordingAction;
        }
    }

    /*
        Class to poll continuously the node status regarding the amount of tests executed. If MAX_UNIQUE_TEST_SESSIONS
        have been executed, then the node is removed from the grid (this should trigger the docker container to stop).
     */
    static class DockerSeleniumNodePoller extends Thread {

        private static long sleepTimeBetweenChecks = 500;
        private DockerSeleniumRemoteProxy dockerSeleniumRemoteProxy = null;

        DockerSeleniumNodePoller(DockerSeleniumRemoteProxy dockerSeleniumRemoteProxy) {
            this.dockerSeleniumRemoteProxy = dockerSeleniumRemoteProxy;
        }

        protected long getSleepTimeBetweenChecks() {
            return sleepTimeBetweenChecks;
        }

        @Override
        public void run() {
            while (true) {
                /*
                    If the proxy is not busy and it can be released since the MAX_UNIQUE_TEST_SESSIONS have been executed,
                    then the node executes its teardown.
                */
                if (!dockerSeleniumRemoteProxy.isBusy() && dockerSeleniumRemoteProxy.isTestSessionLimitReached() &&
                        dockerSeleniumRemoteProxy.stopSessionRequestReceived) {
                    dockerSeleniumRemoteProxy.videoRecording(VideoRecordingAction.STOP_RECORDING);
                    shutdownNode();
                    return;
                }

                try {
                    Thread.sleep(getSleepTimeBetweenChecks());
                } catch (InterruptedException e) {
                    LOGGER.log(Level.FINE, dockerSeleniumRemoteProxy.getNodeIpAndPort() + " Error while sleeping the " +
                            "thread, stopping thread execution.", e);
                    Thread.currentThread().interrupt();
                    dockerSeleniumRemoteProxy.ga.trackException(e);
                    return;
                }
            }
        }

        private void shutdownNode() {
            String shutdownReason = String.format("%s Marking the node as down because it was stopped after %s tests.",
                    dockerSeleniumRemoteProxy.getNodeIpAndPort(), MAX_UNIQUE_TEST_SESSIONS);
            try {
                String containerId = dockerSeleniumRemoteProxy.getContainerId();
                dockerClient.stopContainer(containerId, 5);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, dockerSeleniumRemoteProxy.getNodeIpAndPort() + " " + e.getMessage(), e);
                dockerSeleniumRemoteProxy.ga.trackException(e);
            } finally {
                dockerSeleniumRemoteProxy.addNewEvent(new RemoteNotReachableException(shutdownReason));
                dockerSeleniumRemoteProxy.addNewEvent(new RemoteUnregisterException(shutdownReason));
                dockerSeleniumRemoteProxy.teardown();
            }
        }

    }


}
