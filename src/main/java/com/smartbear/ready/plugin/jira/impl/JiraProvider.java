package com.smartbear.ready.plugin.jira.impl;

import com.atlassian.jira.rest.client.api.*;
import com.atlassian.jira.rest.client.api.domain.BasicIssue;
import com.atlassian.jira.rest.client.api.domain.BasicProject;
import com.atlassian.jira.rest.client.api.domain.CimFieldInfo;
import com.atlassian.jira.rest.client.api.domain.CimIssueType;
import com.atlassian.jira.rest.client.api.domain.CimProject;
import com.atlassian.jira.rest.client.api.domain.CustomFieldOption;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.IssueFieldId;
import com.atlassian.jira.rest.client.api.domain.IssueType;
import com.atlassian.jira.rest.client.api.domain.Priority;
import com.atlassian.jira.rest.client.api.domain.Project;
import com.atlassian.jira.rest.client.api.domain.User;
import com.atlassian.jira.rest.client.api.domain.Version;
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue;
import com.atlassian.jira.rest.client.api.domain.input.FieldInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;
import com.eviware.soapui.SoapUI;
import com.eviware.soapui.actions.SoapUIPreferencesAction;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.settings.Settings;
import com.eviware.soapui.model.support.ModelSupport;
import com.eviware.soapui.support.StringUtils;
import com.eviware.soapui.support.UISupport;
import com.smartbear.ready.plugin.jira.clients.AsynchronousIssueRestClientServerEx;
import com.smartbear.ready.plugin.jira.clients.AsynchronousJiraRestClientEx;
import com.smartbear.ready.plugin.jira.clients.AsynchronousJiraRestClientServerEx;
import com.smartbear.ready.plugin.jira.clients.AsynchronousUserSearchRestClient;
import com.smartbear.ready.plugin.jira.clients.AsynchronousUserSearchRestClientExt;
import com.smartbear.ready.plugin.jira.factories.AsynchronousJiraRestClientFactoryEx;
import com.smartbear.ready.plugin.jira.factories.JiraPrefsFactory;
import com.smartbear.ready.plugin.jira.settings.BugTrackerPrefs;
import com.smartbear.ready.plugin.jira.settings.BugTrackerSettings;
import io.atlassian.util.concurrent.Promise;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class JiraProvider implements SimpleBugTrackerProvider {
    private static final Logger logger = LoggerFactory.getLogger(JiraProvider.class);

    private static final String BUG_TRACKER_ISSUE_KEY_NOT_SPECIFIED = "No issue key is specified.";
    private static final String BUG_TRACKER_FILE_NAME_NOT_SPECIFIED = "No file name is specified.";
    private static final String BUG_TRACKER_INCORRECT_FILE_PATH = "Incorrect file path.";
    private static final String BUG_TRACKER_URI_IS_INCORRECT = "The JIRA URL format is incorrect.";
    public static final String BUG_TRACKER_SETTINGS_ARE_NOT_COMPLETELY_SPECIFIED = "Unable to create a JIRA item.\nThe JIRA Integration plugin's settings are not configured or invalid.";
    public static final String INCORRECT_PROTOCOL_IN_THE_JIRA_URL = "\nPerhaps,  you specified the HTTP protocol in the JIRA URL instead of HTTPS.";
    public static final String USER_NAME_NOT_FOUND = "%s user is not found";
    public static final String INCORRECT_PROTOCOL_ERROR_CODE = "301";
    public static final String PRIORITY_FIELD_NAME = "priority";
    public static final String FIX_VERSIONS_FIELD_NAME = "fixVersions";
    public static final String VERSIONS_FIELD_NAME = "versions";
    public static final String COMPONENTS_FIELD_NAME = "components";
    public static final String ASSIGNEE_FIELD_NAME = "assignee";
    public static final String PARENT_FIELD_NAME = "parent";
    public static final String RESOLUTION_FIELD_NAME = "resolution";
    public static final String NAME_FIELD_NAME = "name";
    public static final String VALUE_FIELD_NAME = "value";

    private ModelItem activeElement;
    private JiraRestClient restClient = null;
    private BugTrackerSettings bugTrackerSettings;
    private static JiraProvider instance = null;

    //Properties below exist for reducing number of Jira API calls since every call is very greedy operation
    Iterable<BasicProject> allProjects = null;
    Map<String, Project> requestedProjects = new HashMap<>();
    Iterable<Priority> priorities = null;
    Map<String/*project*/, Map<String/*Issue Type*/, Map<String/*FieldName*/, CimFieldInfo>>> projectFields = new HashMap<>();

    public static JiraProvider getProvider() {
        if (instance == null) {
            instance = new JiraProvider();
        }
        return instance;
    }

    public static void freeProvider() {
        instance = null;
    }

    private JiraProvider() {
        bugTrackerSettings = getBugTrackerSettings();
        if (!settingsComplete(bugTrackerSettings)) {
            logger.error(BUG_TRACKER_URI_IS_INCORRECT);
            UISupport.showErrorMessage(BUG_TRACKER_SETTINGS_ARE_NOT_COMPLETELY_SPECIFIED);
            showSettingsDialog();
            if (!settingsComplete(bugTrackerSettings)) {
                return;
            }
        }
        final AsynchronousJiraRestClientFactoryEx factory = new AsynchronousJiraRestClientFactoryEx();

        try {
            String url = bugTrackerSettings.getUrl();
            URI uri = new URI(url);
            if (bugTrackerSettings.getLogin() != null && ! bugTrackerSettings.getLogin().isEmpty() && ! bugTrackerSettings.getLogin().isBlank() ) {
                restClient = factory.createWithBasicHttpAuthentication(uri, bugTrackerSettings.getLogin(), bugTrackerSettings.getPassword());
            } else {
                BearerHttpAuthenticationHandler handler = new BearerHttpAuthenticationHandler(bugTrackerSettings.getPassword());
                restClient = factory.create(uri, handler);
            }

            logger.info("[JiraProvider].[JiraProvider] restClient", restClient.toString());
        } catch (URISyntaxException e) {
            logger.error(BUG_TRACKER_URI_IS_INCORRECT);
            UISupport.showErrorMessage(BUG_TRACKER_URI_IS_INCORRECT);
        }
    }

    private void showSettingsDialog() {
        SoapUIPreferencesAction.getInstance().show(JiraPrefsFactory.JIRA_PREFS_TITLE);
        createBugTrackerSettings();
    }

    private JiraApiCallResult<Iterable<BasicProject>> getAllProjects() {
        if (allProjects != null) {
            return new JiraApiCallResult<>(allProjects);
        }

        try {
            allProjects = restClient.getProjectClient().getAllProjects().get();
            return new JiraApiCallResult<>(allProjects);
        } catch (InterruptedException | ExecutionException e) {
            logger.error(e.getMessage());
            allProjects = null;
            return new JiraApiCallResult<>(e);
        }
    }

    public List<String> getListOfAllProjects() {
        JiraApiCallResult<Iterable<BasicProject>> projects = getAllProjects();
        if (!projects.isSuccess()) {
            return new ArrayList<>();
        }

        List<String> projectNames = new ArrayList<>();
        for (BasicProject project : projects.getResult()) {
            projectNames.add(project.getKey());
        }

        return projectNames;
    }

    private JiraApiCallResult<Project> getProjectByKey(String key) {
        if (!requestedProjects.containsKey(key)) {
            try {
                requestedProjects.put(key, restClient.getProjectClient().getProject(key).get());
            } catch (InterruptedException | ExecutionException e) {
                logger.error(e.getMessage());
                return new JiraApiCallResult<>(e);
            }
        }
        return new JiraApiCallResult<>(requestedProjects.get(key));
    }

    private JiraApiCallResult<OptionalIterable<IssueType>> getProjectIssueTypes(String projectKey) {
        JiraApiCallResult<Project> project = getProjectByKey(projectKey);
        if (!project.isSuccess()) {
            return new JiraApiCallResult<>(project.getError());
        }
        return new JiraApiCallResult<>(project.getResult().getIssueTypes());
    }

    public List<String> getListOfProjectIssueTypes(String projectKey) {
        JiraApiCallResult<OptionalIterable<IssueType>> result = getProjectIssueTypes(projectKey);
        if (!result.isSuccess()) {
            return new ArrayList<>();
        }

        List<String> issueTypeList = new ArrayList<>();
        OptionalIterable<IssueType> issueTypes = result.getResult();
        for (IssueType issueType : issueTypes) {
            issueTypeList.add(issueType.getName());
        }

        return issueTypeList;
    }

    public CustomFieldOption transformToCustomFieldOption(Object object) {
        if (object instanceof CustomFieldOption) {
            return (CustomFieldOption) object;
        }

        return null;
    }

    public Version transformToVersion(Object object) {
        if (object instanceof Version) {
            return (Version) object;
        }

        return null;
    }

    private JiraApiCallResult<Iterable<Priority>> getAllPriorities() {
        if (priorities == null) {
            final MetadataRestClient client = restClient.getMetadataClient();
            try {
                priorities = client.getPriorities().get();
            } catch (InterruptedException | ExecutionException e) {
                return new JiraApiCallResult<>(e);
            }
        }
        return new JiraApiCallResult<>(priorities);
    }

    private Priority getPriorityByName(String priorityName) {
        JiraApiCallResult<Iterable<Priority>> prioritiesResponse = getAllPriorities();
        if (!prioritiesResponse.isSuccess()) {
            return null;
        }
        for (Priority priority : prioritiesResponse.getResult()) {
            if (priority.getName().equals(priorityName)) {
                return priority;
            }
        }
        return null;
    }

    private JiraApiCallResult<IssueType> getIssueType(String projectKey, String requiredIssueType) {
        JiraApiCallResult<OptionalIterable<IssueType>> issueTypes = getProjectIssueTypes(projectKey);
        if (!issueTypes.isSuccess()) {
            return new JiraApiCallResult<>(issueTypes.getError());
        }
        for (IssueType issueType : issueTypes.getResult()) {
            if (issueType.getName().equals(requiredIssueType)) {
                return new JiraApiCallResult<>(issueType);
            }
        }
        return null;
    }

    public Issue getIssue(String key) {
        try {
            return restClient.getIssueClient().getIssue(key).get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

        return null;
    }

    public Map<String, Map<String, Map<String, CimFieldInfo>>> getProjectFields(String... projects) {
        JiraApiCallResult<Map<String, Map<String, Map<String, CimFieldInfo>>>> projectFieldsResult = getProjectFieldsInternal(projects);
        if (projectFieldsResult.isSuccess()) {
            return projectFieldsResult.getResult();
        }

        return new HashMap<>();
    }

    private JiraApiCallResult<Map<String, Map<String, Map<String, CimFieldInfo>>>> getProjectFieldsInternal(String... projects) {
        List<String> unCachedProjectsList = new ArrayList<>();
        for (String project : projects) {
            if (!projectFields.containsKey(project)) {
                unCachedProjectsList.add(project);
            }
        }
        if (!unCachedProjectsList.isEmpty()) {
            String[] unCachedProjectsArray = new String[unCachedProjectsList.size()];
            unCachedProjectsList.toArray(unCachedProjectsArray);
            GetCreateIssueMetadataOptions options = new GetCreateIssueMetadataOptionsBuilder()
                    .withExpandedIssueTypesFields()
                    .withProjectKeys(unCachedProjectsList.toArray(unCachedProjectsArray))
                    .build();
            try {
                //TODO: just log more information here to make sure changes applied to readyAPI
                logger.info("[JiraProvider].[getProjectFieldsInternal] we reach here");
                IssueRestClient issueRestClient = restClient.getIssueClient();
                Promise<Iterable<CimProject>> cimProjectPromise = issueRestClient.getCreateIssueMetadata(options);
                Iterable<CimProject> cimProjects = cimProjectPromise.get();

                for (CimProject cimProject : cimProjects) {
                    Iterable<CimIssueType> issueTypes = cimProject.getIssueTypes();
                    HashMap<String, Map<String, CimFieldInfo>> issueTypeFields = new HashMap<>();
                    for (CimIssueType currentIssueType : issueTypes) {
                        if (issueRestClient instanceof AsynchronousIssueRestClientServerEx) {
                            try {
                                Map<String, CimFieldInfo> cimFieldInfoMap = new HashMap<>();
                                Promise<Iterable<CimFieldInfo>> promiseCimFieldInfo = ((AsynchronousIssueRestClientServerEx) issueRestClient).getFieldsByIssueId(options, currentIssueType.getId());
                                Iterable<CimFieldInfo> cimFieldInfos = promiseCimFieldInfo.get();
                                for (CimFieldInfo currentCimFieldInfo : cimFieldInfos) {
                                    cimFieldInfoMap.put(currentCimFieldInfo.getId(), currentCimFieldInfo);
                                }
                                currentIssueType = new CimIssueType(currentIssueType.getSelf(),
                                        currentIssueType.getId(), currentIssueType.getName(), currentIssueType.isSubtask(),
                                        currentIssueType.getDescription(), currentIssueType.getIconUri(), cimFieldInfoMap);
                                issueTypeFields.put(currentIssueType.getName(), currentIssueType.getFields());
                            } catch (Exception ex) {
                                logger.error("A error occurred when create issue on Jira server", ex);
                            }
                        } else {
                            issueTypeFields.put(currentIssueType.getName(), currentIssueType.getFields());
                        }
                    }
                    projectFields.put(cimProject.getKey(), issueTypeFields);
                }
            } catch (InterruptedException | ExecutionException e) {
                return new JiraApiCallResult<>(e);
            }
        }
        return new JiraApiCallResult<>(projectFields);
    }

    private CimFieldInfo getFieldInfo(String projectKey, String issueTypeKey, String fieldName) {
        Map<String, Map<String, Map<String, CimFieldInfo>>> projectFields = getProjectFields(projectKey);
        return projectFields.get(projectKey).get(issueTypeKey).get(fieldName);
    }

    private boolean isFieldWithPredefinedValues(String projectKey, String issueTypeKey, String fieldName) {
        CimFieldInfo fieldInfo = getFieldInfo(projectKey, issueTypeKey, fieldName);
        Iterable<Object> allowedValues = fieldInfo.getAllowedValues();
        return allowedValues != null;
    }

    private boolean isArrayValue(String projectKey, String issueTypeKey, String fieldName) {
        CimFieldInfo fieldInfo = getFieldInfo(projectKey, issueTypeKey, fieldName);
        return fieldInfo.getSchema().getType().equalsIgnoreCase("array");
    }

    @Override
    public IssueCreationResult createIssue(String projectKey, String issueTypeKey, String summary, String description, Map<String, Object> extraRequiredValues) {
        //https://bitbucket.org/atlassian/jira-rest-java-client/src/75a64c9d81aad7d8bd9beb11e098148407b13cae/test/src/test/java/samples/Example1.java?at=master
        if (restClient == null) {
            return new IssueCreationResult(BUG_TRACKER_URI_IS_INCORRECT);
        }

        BasicIssue basicIssue;
        try {
            JiraApiCallResult<IssueType> issueType = getIssueType(projectKey, issueTypeKey);
            if (issueType != null && !issueType.isSuccess()) {
                return new IssueCreationResult(issueType.getError().getMessage());
            }

            IssueInputBuilder issueInputBuilder = new IssueInputBuilder(projectKey, issueType.getResult().getId());
            issueInputBuilder.setIssueType(issueType.getResult());
            issueInputBuilder.setProjectKey(projectKey);
            issueInputBuilder.setSummary(summary);
            issueInputBuilder.setDescription(description);
            for (final Map.Entry<String, Object> extraRequiredValue : extraRequiredValues.entrySet()) {
                if (extraRequiredValue.getKey().equals(PRIORITY_FIELD_NAME)) {
                    issueInputBuilder.setPriority(getPriorityByName((String) extraRequiredValue.getValue()));
                } else if (extraRequiredValue.getKey().equals(COMPONENTS_FIELD_NAME)) {
                    issueInputBuilder.setComponentsNames(() -> new Iterator<>() {
                        boolean hasValue = true;

                        @Override
                        public boolean hasNext() {
                            return hasValue;
                        }

                        @Override
                        public String next() {
                            hasValue = false;
                            return (String) extraRequiredValue.getValue();
                        }

                        @Override
                        public void remove() {

                        }
                    });
                } else if (extraRequiredValue.getKey().equals(VERSIONS_FIELD_NAME)) {
                    issueInputBuilder.setAffectedVersionsNames(() -> new Iterator<>() {
                        boolean hasValue = true;

                        @Override
                        public boolean hasNext() {
                            return hasValue;
                        }

                        @Override
                        public String next() {
                            hasValue = false;
                            return (String) extraRequiredValue.getValue();
                        }

                        @Override
                        public void remove() {

                        }
                    });
                } else if (extraRequiredValue.getKey().equals(FIX_VERSIONS_FIELD_NAME)) {
                    issueInputBuilder.setFixVersionsNames(() -> new Iterator<>() {
                        boolean hasValue = true;

                        @Override
                        public boolean hasNext() {
                            return hasValue;
                        }

                        @Override
                        public String next() {
                            hasValue = false;
                            return (String) extraRequiredValue.getValue();
                        }

                        @Override
                        public void remove() {

                        }
                    });
                } else if (extraRequiredValue.getKey().equals(ASSIGNEE_FIELD_NAME)) {
                    issueInputBuilder.setFieldInput(getUserFieldInput(IssueFieldId.ASSIGNEE_FIELD.id, (String) extraRequiredValue.getValue()));
                } else if (extraRequiredValue.getKey().equals(PARENT_FIELD_NAME)) {
                    Map<String, Object> parent = new HashMap<>();
                    parent.put("key", extraRequiredValue.getValue());
                    FieldInput parentField = new FieldInput(PARENT_FIELD_NAME, new ComplexIssueInputFieldValue(parent));
                    issueInputBuilder.setFieldInput(parentField);
                } else if (extraRequiredValue.getKey().equals(RESOLUTION_FIELD_NAME)) {
                    Map<String, Object> customOptionValue = new HashMap<>();
                    customOptionValue.put(NAME_FIELD_NAME, extraRequiredValue.getValue());
                    issueInputBuilder.setFieldValue(extraRequiredValue.getKey(), new ComplexIssueInputFieldValue(customOptionValue));
                } else if (extraRequiredValue.getKey().equals(IssueFieldId.REPORTER_FIELD.id)) {
                    issueInputBuilder.setFieldInput(getUserFieldInput(IssueFieldId.REPORTER_FIELD.id, (String) extraRequiredValue.getValue()));
                } else if (isFieldWithPredefinedValues(projectKey, issueTypeKey, extraRequiredValue.getKey())) {
                    List<ComplexIssueInputFieldValue> fieldValueList = new ArrayList<>();
                    String[] values = (String[]) extraRequiredValue.getValue();
                    for (String value : values) {
                        Map<String, Object> valueMap = new HashMap<>();
                        valueMap.put(VALUE_FIELD_NAME, value);
                        fieldValueList.add(new ComplexIssueInputFieldValue(valueMap));
                    }
                    issueInputBuilder.setFieldValue(extraRequiredValue.getKey(), fieldValueList);
                } else if (isArrayValue(projectKey, issueTypeKey, extraRequiredValue.getKey())) {
                    issueInputBuilder.setFieldValue(extraRequiredValue.getKey(), Arrays.asList(((String) extraRequiredValue.getValue()).split("\\s*,\\s*")));
                } else {
                    issueInputBuilder.setFieldValue(extraRequiredValue.getKey(), extraRequiredValue.getValue());
                }
            }
            Promise<BasicIssue> issue = restClient.getIssueClient().createIssue(issueInputBuilder.build());
            basicIssue = issue.get();
        } catch (InterruptedException | ExecutionException e) {
            String errorMessage = e.getMessage();
            if (errorMessage.contains(INCORRECT_PROTOCOL_ERROR_CODE)) {
                errorMessage += INCORRECT_PROTOCOL_IN_THE_JIRA_URL;
            }
            return new IssueCreationResult(errorMessage);
        } catch (Exception e) {
            return new IssueCreationResult(e.getMessage());
        }

        return new IssueCreationResult(basicIssue);
    }

    private FieldInput getUserFieldInput(String key, String value) throws Exception {
        ComplexIssueInputFieldValue complexIssueInputFieldValue;
        User user = getUser(value);
        String username = user.getName();
        if (username != null) {
            complexIssueInputFieldValue = ComplexIssueInputFieldValue.with("name", username);
        } else {
            String accountId = user.getAccountId();
            complexIssueInputFieldValue = ComplexIssueInputFieldValue.with("accountId", accountId);
        }
        return new FieldInput(key, complexIssueInputFieldValue);
    }

    private User getUser(String username) throws Exception {
        User user = null;
        if (restClient instanceof AsynchronousJiraRestClientEx) {
            AsynchronousUserSearchRestClient userSearchRestClient = ((AsynchronousJiraRestClientEx) restClient).getUserSearchRestClient();
            user = userSearchRestClient.getUser(username).get();
        } else if (restClient instanceof AsynchronousJiraRestClientServerEx) {
            AsynchronousUserSearchRestClientExt userSearchRestClientExt = ((AsynchronousJiraRestClientServerEx) restClient).getUserSearchRestClient();
            user = userSearchRestClientExt.getUser(username).get();
        }
        if (user == null) {
            throw new Exception(String.format(USER_NAME_NOT_FOUND, username));
        }
        return user;
    }

    private String getUserName(String username) throws Exception {
        User user = getUser(username);
        return user.getName();
    }

    protected void finalize() throws Throwable {
        try {
            if (restClient != null) {
                restClient.close();
            }
        } catch (IOException e) {
        }
    }

    @Override
    public AttachmentAddingResult attachFile(URI attachmentUri, String fileName, InputStream inputStream) {
        if (attachmentUri == null) {
            return new AttachmentAddingResult(BUG_TRACKER_ISSUE_KEY_NOT_SPECIFIED);
        }
        if (StringUtils.isNullOrEmpty(fileName)) {
            return new AttachmentAddingResult(BUG_TRACKER_FILE_NAME_NOT_SPECIFIED);
        }

        try {
            restClient.getIssueClient().addAttachment(attachmentUri, inputStream, fileName).get();
        } catch (InterruptedException | ExecutionException e) {
            return new AttachmentAddingResult(e.getMessage());
        }

        return new AttachmentAddingResult();//everything is ok
    }

    @Override
    public AttachmentAddingResult attachFile(URI attachmentUri, String filePath) {
        if (attachmentUri == null) {
            return new AttachmentAddingResult(BUG_TRACKER_ISSUE_KEY_NOT_SPECIFIED);
        }
        if (StringUtils.isNullOrEmpty(filePath)) {
            return new AttachmentAddingResult(BUG_TRACKER_INCORRECT_FILE_PATH);
        }
        File file = new File(filePath);
        if (!file.exists() && file.isFile()) {
            return new AttachmentAddingResult(BUG_TRACKER_INCORRECT_FILE_PATH);
        }

        restClient.getIssueClient().addAttachments(attachmentUri, file);
        return new AttachmentAddingResult();
    }

    private InputStream getExecutionLog() {
        Appender appender = ((LoggerContext) LogManager.getContext(false)).getConfiguration().getAppender("FILE");
        if (appender instanceof RollingFileAppender) {
            try {
                return new FileInputStream(((RollingFileAppender) appender).getFileName());
            } catch (FileNotFoundException e) {
                JiraProvider.logger.error(e.getMessage());
            }
        }

        return null;
    }

    //TODO: Specify an appenderName for getExecutionLog()
    public InputStream getServiceVExecutionLog() {
        return getExecutionLog();
    }

    //TODO: Specify an appenderName for getExecutionLog()
    public InputStream getLoadUIExecutionLog() {
        return getExecutionLog();
    }

    public InputStream getReadyApiLog() {
        return getExecutionLog();
    }

    public void setActiveItem(ModelItem element) {
        activeElement = element;
    }

    public String getActiveItemName() {
        return activeElement.getName();
    }

    public String getRootProjectName() {
        WsdlProject project = findActiveElementRootProject(activeElement);
        return project.getName();
    }

    public InputStream getRootProject() {
        WsdlProject project = findActiveElementRootProject(activeElement);
        return new ByteArrayInputStream(project.getProjectDocument()
                .toString().getBytes(StandardCharsets.UTF_8));
    }

    private WsdlProject findActiveElementRootProject(ModelItem activeElement) {
        return ModelSupport.getModelItemProject(activeElement);
    }

    public boolean settingsComplete(BugTrackerSettings settings) {
        return !(settings == null ||
                StringUtils.isNullOrEmpty(settings.getUrl()) ||
                //StringUtils.isNullOrEmpty(settings.getLogin()) ||
                StringUtils.isNullOrEmpty(settings.getPassword()));
    }

    public boolean settingsComplete() {
        BugTrackerSettings settings = getBugTrackerSettings();
        return settingsComplete(settings);
    }

    public BugTrackerSettings getBugTrackerSettings() {
        if (bugTrackerSettings == null) {
            createBugTrackerSettings();
        }
        return bugTrackerSettings;
    }

    private void createBugTrackerSettings() {
        Settings soapuiSettings = SoapUI.getSettings();
        bugTrackerSettings = new BugTrackerSettings(soapuiSettings.getString(BugTrackerPrefs.DEFAULT_URL, ""),
                soapuiSettings.getString(BugTrackerPrefs.LOGIN, ""),
                soapuiSettings.getString(BugTrackerPrefs.PASSWORD, ""),
                soapuiSettings.getBoolean(BugTrackerPrefs.SKIP_VERSIONS, false));
    }
}