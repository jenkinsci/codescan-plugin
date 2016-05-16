package com.villagechief.codescan.jenkins;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.BuildException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * CodeScan {@link Builder}.
 *
 */
public class CodeScanBuilder extends Builder implements SimpleBuildStep {
	private static final String DEFAULT_SERVER_URL = "https://app.code-scan.com/api/job";
	private static final int DEFAULT_MAX_SECONDS = 900;
	private final String projectKey;
	private final String commitOverride;
	private final String version;
	private final String emailReportTo;
	private final String analysisMode;
	private final String projectBranch;
	private final boolean blocking;
	
	private String outputStatus;
	private String outputStatusAlert;
	private String outputStatusAlertDescription;
	private String outputUrl;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public CodeScanBuilder(
    		String projectKey,
    		String commitOverride,
    		String version,
    		String emailReportTo,
    		String analysisMode,
    		String projectBranch,
    		Boolean blocking
    	) {
        this.projectKey = projectKey;
        this.commitOverride = commitOverride;
        this.version = version;
        this.emailReportTo = emailReportTo;
        this.analysisMode = analysisMode;
        this.projectBranch = projectBranch;
        this.blocking = StringUtils.isEmpty(projectKey) ? true : blocking;
    }

	protected String getCreateJob(TaskListener listener) throws IOException{
		JsonObject output = new JsonObject();
		output.addProperty("projectKey", projectKey);
		output.addProperty("commitOverride", getCommitOverride());
		output.addProperty("version", getVersion());
		output.addProperty("emailReportTo", getEmailReportTo());
		output.addProperty("analysisMode", getAnalysisMode());
		output.addProperty("projectBranch", getProjectBranch());

		String serverUrl = getDescriptor().getServerUrl();
        if ( StringUtils.isEmpty(serverUrl) )
        	serverUrl = DEFAULT_SERVER_URL;
        
		listener.getLogger().println("Creating job for project #"+ projectKey);
		URL url = new URL(serverUrl);
		HttpURLConnection uc = (HttpURLConnection) url.openConnection();
		String userpass = getDescriptor().getSubscriptionId() + ":" + getDescriptor().getApiKey();
		uc.setRequestProperty ("Authorization", "Basic " + Base64.encodeBase64URLSafeString(userpass.getBytes("UTF-8")));
		
		//add request header
		uc.setRequestMethod("POST");
		uc.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
		uc.setRequestProperty("Content-Type", "application/json");

		// Send post request
		uc.setDoOutput(true);
		OutputStreamWriter wr = new OutputStreamWriter(uc.getOutputStream(), "UTF-8");
		wr.write(output.toString());
		wr.flush();
		wr.close();

		if ( uc.getResponseCode() == 200 ){
    		String json = IOUtils.toString(uc.getInputStream());
    		JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
    
    		if ( obj.has("error") ){
    			if ( obj.has("message") )
    				throw new CodeScanException(obj.get("message").getAsString());
    			else
    				throw new CodeScanException("Unknown server error");
    		}else{
        		if ( obj.has("warnings") ){
        			JsonArray warnings = obj.getAsJsonArray("warnings");
        			for ( JsonElement warning : warnings ){
        				listener.error(warning.getAsString());
        			}
        		}
        		
    			return obj.get("jobId").getAsString();
    		}
		}else{
			String err = IOUtils.toString(uc.getErrorStream()==null? uc.getInputStream() : uc.getErrorStream());
			listener.fatalError(err);
			throw new CodeScanException("Server returned HTTP response code: " + uc.getResponseCode() + " for URL: " + url);
		}
	}

	protected void getJobStatus(TaskListener listener, String jobId) throws IOException{
		String serverUrl = getDescriptor().getServerUrl();
        if ( StringUtils.isEmpty(serverUrl) )
        	serverUrl = DEFAULT_SERVER_URL;
        
		URL url = new URL(serverUrl + "?jobId=" + URLEncoder.encode(jobId, "UTF-8"));
		HttpURLConnection uc = (HttpURLConnection)url.openConnection();
		String userpass = getDescriptor().getSubscriptionId() + ":" + getDescriptor().getApiKey();
		uc.setRequestProperty ("Authorization", "Basic " + Base64.encodeBase64URLSafeString(userpass.getBytes("UTF-8")));
		
		if ( uc.getResponseCode() == 200 ){
    		String json = IOUtils.toString(uc.getInputStream());
    		JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
    		
    		if ( obj.has("error") ){
    			if ( obj.has("message") )
    				throw new CodeScanException(obj.get("message").getAsString());
    			else
    				throw new CodeScanException("Unknown error");
    		}else{
        		outputStatus = obj.get("status").getAsString();
        		outputStatusAlert = obj.has("alert") ? obj.get("alert").getAsString() : "";
        		outputStatusAlertDescription = obj.has("alertDescription") ? obj.get("alertDescription").getAsString() : "";
        		outputUrl = obj.get("url").getAsString();
    		}

    		if ( outputUrl == null )
    			outputUrl = "";
    		if ( outputStatus == null )
    			outputStatus = "failed";
    		if ( outputStatusAlert == null )
    			outputStatusAlert = "";
    		if ( outputStatusAlertDescription == null )
    			outputStatusAlertDescription = "";
		}else{
    		String err = IOUtils.toString(uc.getErrorStream() == null ? uc.getInputStream() : uc.getErrorStream());
    		listener.fatalError(err);
    		throw new CodeScanException("Server returned HTTP response code: " + uc.getResponseCode() + " for URL: " + url);
    	}
	}
	
	private void getStatus(TaskListener listener, String jobId){
		listener.getLogger().println("Getting job status for #"+ jobId);

		try{
			getJobStatus(listener, jobId);
			
			long expires = System.currentTimeMillis() + (1000 * getDescriptor().maximumSeconds);
			int delay = 10;
			while ( true ){
    			if ( StringUtils.equals(outputStatus, "done") || StringUtils.equals(outputStatus, "failed") ){
    				break;
    			}
    			
    			if ( System.currentTimeMillis() >= expires ){
        			listener.fatalError("Timed out waiting for " + getDescriptor().maximumSeconds + " seconds");
        			throw new CodeScanException("Timed out waiting for " + getDescriptor().maximumSeconds + " seconds");
    			}
    			
    			delay = Math.min(30, delay); //check at least every 30 seconds
    			listener.getLogger().println("Status: " + outputStatus + ". Trying again in " + delay + " seconds...");
    			Thread.sleep(delay * 1000);
    			delay += 5;
    			getJobStatus(listener, jobId);
			}
			
		}catch(IOException e){
			throw new CodeScanException("Could not fetch job", e);
		} catch (InterruptedException e) {
			throw new CodeScanException("Job cancelled", e);
        }
	}
	
    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException {
		if (StringUtils.isBlank(getDescriptor().subscriptionId)) {
			throw new CodeScanException("No subscriptionId defined - check global configuration");
		}
		if (StringUtils.isBlank(getDescriptor().apiKey)) {
			throw new CodeScanException("No apiKey defined - check global configuration");
		}
    	
		String jobId = getCreateJob(listener);
		//log message
		listener.getLogger().println("Job #" + jobId + " created...");
		
		if ( isBlocking() ){
			getStatus(listener, jobId);

			//log message
			listener.getLogger().println("Status: " + outputStatus + ".");
			if ( !StringUtils.isEmpty(outputStatusAlert) ){
				listener.getLogger().println(" " + outputStatusAlert + ": " + outputStatusAlertDescription); 
			}else{
				listener.getLogger().println(" no Quality Gate configured.");
			}

			if ( !outputUrl.isEmpty() ){
				listener.getLogger().print("Project report: ");
				listener.hyperlink(outputUrl, outputUrl);
				listener.getLogger().println("");
			}


			if (StringUtils.equals(outputStatus, "failed") ){
				listener.fatalError("Build failed");
			}else if (StringUtils.equals(outputStatusAlert, "ERROR") ){
				listener.fatalError("Build Condition Failed: " + outputStatusAlertDescription);
			}
		}
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link CodeScanBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
    	private String subscriptionId;
    	private String apiKey;
    	private String serverUrl;
		private Integer maximumSeconds;

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckProjectKey(@QueryParameter String value){
            if (value.length() == 0)
                return FormValidation.error("Please set a project key");
            return FormValidation.ok();
        }
        public FormValidation doCheckCommitOverride(@QueryParameter String value){
            return FormValidation.ok();
        }
        public FormValidation doCheckVersion(@QueryParameter String value){
            return FormValidation.ok();
        }
        public FormValidation doCheckEmailReportTo(@QueryParameter String value){
            return FormValidation.ok();
        }
        public FormValidation doCheckAnalysisMode(@QueryParameter String value){
        	if ( value.length() == 0 ){
                return FormValidation.error("Please select an analysis mode.");
        	}
            return FormValidation.ok();
        }
        public ListBoxModel doFillAnalysisModeItems(){
        	ListBoxModel items = new ListBoxModel();
            items.add("Publish (default)", "publish");
            items.add("Preview only", "preview");
            items.add("Issues (changed files only)", "issues");
            return items;
        }
        public FormValidation doCheckProjectBranch(@QueryParameter String value){
            return FormValidation.ok();
        }
        public FormValidation doCheckBlocking(@QueryParameter String value){
            return FormValidation.ok();
        }
        public FormValidation doCheckMaximumSeconds(@QueryParameter Integer value){
        	if ( value == null || value < 180 ){
                return FormValidation.error("Please set a timeout of at least 180 seconds.");
        	}
            return FormValidation.ok();
        }
    	
        public FormValidation doCheckSubscriptionId(@QueryParameter String value){
            if (value.length() == 0)
                return FormValidation.error("Please set a subscription Id");
            return FormValidation.ok();
        }
        public FormValidation doCheckApiKey(@QueryParameter String value){
            if (value.length() == 0)
                return FormValidation.error("Please set a api key");
            return FormValidation.ok();
        }
        public FormValidation doCheckServerUrl(@QueryParameter String value){
            if (value.length() != 0){
            	try {
            		URL url = new URL(value);
            		if ( StringUtils.isEmpty(url.getProtocol()) || 
            				StringUtils.isEmpty(url.getHost()) ){
                		return FormValidation.error("Please set a complete URL");
            		}
            	}catch(Throwable e){
            		return FormValidation.error("Please set a valid URL");
            	}
            }
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Build a CodeScan Project";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
        	subscriptionId = formData.getString("subscriptionId");
        	apiKey = formData.getString("apiKey");
        	serverUrl = formData.getString("serverUrl");
        	maximumSeconds = formData.getInt("maximumSeconds");
        	if ( maximumSeconds == 0 )
        		maximumSeconds = DEFAULT_MAX_SECONDS;
        	
            save();
            return super.configure(req,formData);
        }

    	public String getSubscriptionId() {
    		return subscriptionId;
    	}

    	public String getApiKey() {
    		return apiKey;
    	}

    	public String getServerUrl() {
    		return serverUrl;
    	}

    	public int getMaximumSeconds() {
    		return maximumSeconds == null ? DEFAULT_MAX_SECONDS : maximumSeconds;
    	}
    }

	public String getProjectKey() {
		return projectKey;
	}

	public String getCommitOverride() {
		return commitOverride;
	}

	public String getVersion() {
		return version;
	}

	public String getEmailReportTo() {
		return emailReportTo;
	}

	public String getAnalysisMode() {
		return analysisMode;
	}

	public String getProjectBranch() {
		return projectBranch;
	}

	public boolean isBlocking() {
		return blocking;
	}
}

