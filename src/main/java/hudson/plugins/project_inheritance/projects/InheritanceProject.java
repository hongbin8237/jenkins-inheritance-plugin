/**
 * Copyright (c) 2018-2019 Intel Corporation
 * Copyright (c) 2015-2017 Intel Deutschland GmbH
 * Copyright (c) 2011-2015 Intel Mobile Communications GmbH
 *
 *
 * This file is part of the Inheritance plug-in for Jenkins.
 *
 * The Inheritance plug-in is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation in version 3
 * of the License
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 */
package hudson.plugins.project_inheritance.projects;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import javax.annotation.CheckForNull;
import javax.servlet.ServletException;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkins.ui.icon.Icon;
import org.jenkins.ui.icon.IconSet;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.w3c.dom.Document;

import com.google.common.base.Joiner;
import com.sun.mail.util.BASE64EncoderStream;
import com.thoughtworks.xstream.XStreamException;

import difflib.DiffUtils;
import difflib.Patch;
import hudson.BulkChange;
import hudson.Extension;
import hudson.Functions;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.DependencyGraph;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Label;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Project;
import hudson.model.Queue;
import hudson.model.StringParameterValue;
import hudson.model.TopLevelItem;
import hudson.model.TransientProjectActionFactory;
import hudson.model.Cause.RemoteCause;
import hudson.model.Cause.UserIdCause;
import hudson.model.Descriptor.FormException;
import hudson.model.listeners.ItemListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.queue.ScheduleResult;
import hudson.model.queue.SubTask;
import hudson.model.queue.SubTaskContributor;
import hudson.plugins.project_inheritance.projects.InheritanceProject.Relationship.Type;
import hudson.plugins.project_inheritance.projects.actions.VersioningAction;
import hudson.plugins.project_inheritance.projects.causes.BuildCauseOverride;
import hudson.plugins.project_inheritance.projects.creation.ProjectCreationEngine;
import hudson.plugins.project_inheritance.projects.creation.ProjectCreationEngine.CreationClass;
import hudson.plugins.project_inheritance.projects.inheritance.InheritanceGovernor;
import hudson.plugins.project_inheritance.projects.inheritance.ParameterSelector;
import hudson.plugins.project_inheritance.projects.inheritance.ParameterSelector.ScopeEntry;
import hudson.plugins.project_inheritance.projects.parameters.InheritableStringParameterDefinition;
import hudson.plugins.project_inheritance.projects.parameters.InheritableStringParameterReferenceDefinition;
import hudson.plugins.project_inheritance.projects.parameters.InheritanceParametersDefinitionProperty;
import hudson.plugins.project_inheritance.projects.parameters.InheritableStringParameterDefinition.IModes;
import hudson.plugins.project_inheritance.projects.rebuild.InheritanceRebuildAction;
import hudson.plugins.project_inheritance.projects.rebuild.RebuildCause;
import hudson.plugins.project_inheritance.projects.references.AbstractProjectReference;
import hudson.plugins.project_inheritance.projects.references.ParameterizedProjectReference;
import hudson.plugins.project_inheritance.projects.references.ProjectReference;
import hudson.plugins.project_inheritance.projects.references.Referencer;
import hudson.plugins.project_inheritance.projects.references.SimpleProjectReference;
import hudson.plugins.project_inheritance.projects.references.ProjectReference.PrioComparator;
import hudson.plugins.project_inheritance.projects.references.ProjectReference.PrioComparator.SELECTOR;
import hudson.plugins.project_inheritance.projects.versioning.VersionChangeListener;
import hudson.plugins.project_inheritance.projects.versioning.VersionHandler;
import hudson.plugins.project_inheritance.projects.view.BuildFlowScriptAction;
import hudson.plugins.project_inheritance.projects.view.BuildViewExtension;
import hudson.plugins.project_inheritance.util.Helpers;
import hudson.plugins.project_inheritance.util.MockItemGroup;
import hudson.plugins.project_inheritance.util.ThreadAssocStore;
import hudson.plugins.project_inheritance.util.TimedBuffer;
import hudson.plugins.project_inheritance.util.VersionedObjectStore;
import hudson.plugins.project_inheritance.util.VersionedObjectStore.Version;
import hudson.plugins.project_inheritance.util.VersionsNotification;
import hudson.plugins.project_inheritance.util.exceptions.HttpStatusException;
import hudson.plugins.project_inheritance.util.svg.Graph;
import hudson.plugins.project_inheritance.util.svg.SVGNode;
import hudson.plugins.project_inheritance.util.svg.renderers.SVGTreeRenderer;
import hudson.plugins.project_inheritance.widgets.ExtendedBuildHistoryWidget;
import hudson.scm.NullSCM;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.security.PermissionScope;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildWrapper;
import hudson.tasks.Builder;
import hudson.tasks.LogRotator;
import hudson.tasks.Publisher;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.DescribableList;
import hudson.util.FormApply;
import hudson.util.ListBoxModel;
import hudson.widgets.BuildHistoryWidget;
import hudson.widgets.HistoryWidget;
import hudson.widgets.Widget;
import jenkins.model.BuildDiscarder;
import jenkins.model.BuildDiscarderProperty;
import jenkins.model.Jenkins;
import jenkins.scm.SCMCheckoutStrategy;
import jenkins.util.TimeDuration;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/**
 * A simple base class for all inheritable jobs/projects.
 * 
 * TODO: Create suitable JavaDoc description for this class
 * 
 * @author Martin Schroeder
 *
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class InheritanceProject extends Project<InheritanceProject, InheritanceBuild>
		implements TopLevelItem, Comparable<Project>, SVGNode {
	
	private static final Logger log = Logger.getLogger(
			InheritanceProject.class.toString()
	);
	
	/**
	 * This lock is used, to ensure that only one project at a time rebuilds the
	 * graph.
	 * <p>
	 * Previously, this class had to use a lot of synchronized methods, which
	 * made building graphs for two projects at a time dangerous. Nowadays,
	 * with Jenkins > 1.625, the graphs could probably be built in parallel,
	 * but it is only a minor performance penalty to build the graphs serially.
	 */
	private static ReentrantLock globalGraphBuildingLock = new ReentrantLock();
	
	// === NESTED CLASS AND ENUM DEFINITIONS ===
	
	/**
	 * A very simple enum for the possible relationship states between
	 * to projects.
	 */
	public static class Relationship {
		public enum Type {
			PARENT, MATE, CHILD;
			
			@Override
			public String toString() {
				switch (this) {
					case PARENT:
						return Messages.InheritanceProject_Relationship_Type_Parent();
					case MATE:
						return Messages.InheritanceProject_Relationship_Type_Mate();
					case CHILD:
						return Messages.InheritanceProject_Relationship_Type_Child();
					default:
						return "N/A";
				}
			}
			
			public String getDescription() {
				switch (this) {
					case PARENT:
						return Messages.InheritanceProject_Relationship_Type_ParentDesc();
					case MATE:
						return Messages.InheritanceProject_Relationship_Type_MateDesc();
					case CHILD:
						return Messages.InheritanceProject_Relationship_Type_ChildDesc();
					default:
						return "N/A";
				}
			}
		}
		public final Type type;
		public final int distance;
		public final boolean isLeaf;
		
		public Relationship(Type type, int distance, boolean isLeaf) {
			this.type = type;
			this.distance = distance;
			this.isLeaf = isLeaf;
		}
	}
	
	public class ParameterDerivationDetails implements Comparable<ParameterDerivationDetails> {
		private final String parameterName;
		private final String projectName;
		private final String detail;
		private final Object defaultValue;
		private int order = 0;
		
		public ParameterDerivationDetails(
				String paramName, String projectName, String detail, Object defaultValue) {
			this.parameterName = paramName;
			this.projectName = projectName;
			this.detail = detail;
			this.defaultValue = defaultValue;
			
			if (this.parameterName == null || this.projectName == null) {
				throw new NullPointerException();
			}
		}
		
		public String getParameterName() {
			return parameterName;
		}
		
		public String getProjectName() {
			return this.projectName;
		}
		
		public String getDetail() {
			return this.detail;
		}
		
		public String getProjectAndDetail() {
			if (this.detail != null && this.detail.length() > 0) {
				return this.projectName + "(" + detail + ")";
			} else {
				return this.projectName;
			}
		}
		
		public String getDefault() {
			if (this.defaultValue == null) {
				return "NULL";
			} else {
				return this.defaultValue.toString();
			}
		}
		
		public int getOrder() {
			return order;
		}
		
		public void setOrder(int order) {
			this.order = order;
		}
		
		@Override
		public boolean equals(Object other) {
			if (!(other instanceof ParameterDerivationDetails)) {
				return false;
			}
			ParameterDerivationDetails o = (ParameterDerivationDetails) other;
			
			return (
				Helpers.bothNullOrEqual(parameterName, o.parameterName) &&
				Helpers.bothNullOrEqual(projectName, o.projectName) &&
				Helpers.bothNullOrEqual(detail, o.detail) &&
				Helpers.bothNullOrEqual(defaultValue, o.defaultValue)
			);
		}

		public int compareTo(ParameterDerivationDetails o) {
			if (!Helpers.bothNullOrEqual(parameterName, o.parameterName)) {
				return parameterName.compareTo(parameterName);
			}
			if (!Helpers.bothNullOrEqual(projectName, o.projectName)) {
				return projectName.compareTo(projectName);
			}
			if (!Helpers.bothNullOrEqual(detail, o.detail)) {
				return detail.compareTo(detail);
			}
			if (!Helpers.bothNullOrEqual(defaultValue, o.defaultValue)) {
				return defaultValue.toString().compareTo(defaultValue.toString());
			}
			return 0;
		}
	
		
	}
	
	public static enum IMode {
		LOCAL_ONLY, INHERIT_FORCED, AUTO;
	}
	
	
	// === PRIVATE/PROTECTED STATIC FIELDS ===
	
	/**
	 * This buffer is used for objects that don't need to be repeatedly
	 * generated, as long as the configuration of this project or its
	 * parents has not changed.
	 * 
	 * This class ensures that this buffer is cleared whenever the project or
	 * its parents are changed.
	 * 
	 * @see #createBuffers()
	 * @see #clearBuffers(InheritanceProject)
	 */
	protected static TimedBuffer<InheritanceProject, String> onInheritChangeBuffer = null;
	
	/**
	 * Same as {@link #onSelfChangeBuffer}, but this buffer is cleared only
	 * when the project itself is changed.
	 * 
	 * @see #createBuffers() 
	 * @see #clearBuffers(InheritanceProject)
	 */
	protected static TimedBuffer<InheritanceProject, String> onSelfChangeBuffer = null;
	
	/**
	 * Same as {@link #onSelfChangeBuffer}, but this buffer is cleared when
	 * <i>any</i> project is changed or loaded anew.
	 * 
	 * @see #createBuffers()
	 * @see #clearBuffers(InheritanceProject)
	 */
	protected static TimedBuffer<InheritanceProject, String> onChangeBuffer = null;
	
	public static Permission VERSION_CONFIG = new Permission(
			PERMISSIONS, "ConfigureVersions",
			Messages._InheritanceProject_VersionsConfigPermissionDescription(),
			Jenkins.ADMINISTER,
			PermissionScope.ITEM
	);
	
	/**
	 * This blank static initializer method will ensure that the class is loaded on start-up 
	 * and that the VERSION_CONFIG permission is created.
	 */
	@Initializer(after=InitMilestone.STARTED)
	public static void createConfigureVersionsPermission() {}
	
	// === PRIVATE/PROTECTED MEMBER FIELDS ===
	
	/**
	 * This field is only valid for transient jobs.
	 * It carries the additional, optional "variance" part as assigned by the
	 * {@link ProjectCreationEngine} during its creation.
	 */
	protected transient String variance = null;
	
	/**
	 * This {@link VersionedObjectStore} is used to version all configurable
	 * properties of this class.
	 * <p>
	 * Do note that transient projects (see {@link #isTransient}) do not do
	 * versioning and always have an empty store. This is because they don't
	 * actually have a configuration of their own.
	 */
	protected transient VersionedObjectStore versionStore = null;
	
	
	// === FIELDS SET BY JELLY FORM TAGS ===
	
	/**
	 * Flag to denote a transient project that is not serialized to disk.
	 */
	protected final boolean isTransient;
	
	/**
	 * Flag to denote a project that can't be built directly; but contrary to
	 * to the {@link #isBuildable()} value, additionally means that certain
	 * checks for inheritance consistence are relaxed.
	 * 
	 * Not hidden, because the getting/setting this value is not checked anyway.
	 */
	public boolean isAbstract = false;
	
	/**
	 * This stores the name of the creation class this project falls in.
	 * @see ProjectCreationEngine
	 */
	protected String creationClass = null;
	
	/**
	 * This list stores references to the projects this project was marked as
	 * being compatible with. For each project referenced in this list, the
	 * {@link ProjectCreationEngine} will try to create a new, transient
	 * project derived from both this project and the referenced one.
	 * It also checks if:
	 * <ol>
	 *   <li>
	 *	 The referenced project is compatible with this project
	 *	 (see {@link #creationClass}),
	 *   </li>
	 *   <li>all parameters are correctly set,</li>
	 *   <li>no circular, diamond or multiple inheritance is created,</li>
	 *   <li>the resulting project is buildable and</li>
	 *   <li>the newly created job does not already exist.</li>
	 * </ol>
	 */
	protected LinkedList<AbstractProjectReference> compatibleProjects =
			new LinkedList<AbstractProjectReference>();
	
	/**
	 * This list stores the adjacency relationship of this project to its
	 * parents. The order of objects is in <i>most</i> cases unimportant, as
	 * the {@link ProjectReference} class itself stores priorization details.
	 * <p>
	 * Do note that any {@link AbstractProjectReference} not derived from
	 * {@link ProjectReference} does not carry priority information and thus
	 * treated as having a priority of 0 everywhere.
	 */
	protected LinkedList<AbstractProjectReference> parentReferences =
			new LinkedList<AbstractProjectReference>();
	
	protected String parameterizedWorkspace;
	
	protected Object readResolve() {
		if (parentReferences == null) {
			parentReferences =
					new LinkedList<AbstractProjectReference>();
		}
		if (compatibleProjects == null) {
			compatibleProjects =
					new LinkedList<AbstractProjectReference>();
		}
		
		return this;
	}
	
	
	// === CONSTRUCTORS AND CONSTRUCTION HELPERS ===
	
	public InheritanceProject(ItemGroup parent, String name, boolean isTransient) {
		super(parent, name);
		this.isTransient = isTransient;
		
		//Creating the static buffers, if necessary
		createBuffers();
		
		this.versionStore = this.loadVersionedObjectStore();
		
		//Generating a new IP causes a refresh of the project map and buffers
		clearBuffers(null);
	}

	public int compareTo(Project o) {
		return this.name.compareTo(o.getFullName());
	}
	
	@Override
	public String toString() {
		return this.getFullName();
	}
	
	public String getIconFileName() {
		return "/plugin/project-inheritance/images/64x64/gear.png";
	}
	
	@Override
	protected Class<InheritanceBuild> getBuildClass() {
		return InheritanceBuild.class;
	}
	
	/**
	 * This method returns a mapping of project names to the
	 * {@link InheritanceProject} objects that carry that name.
	 * 
	 * Do note that this method is using aggressive buffering, to make sure
	 * that repeated access is running in O(1), instead of having to scan
	 * all defined projects again and again.
	 * 
	 * The downside of this, is that you have to call
	 * {@link #clearBuffers(InheritanceProject)} with null, whenever a change
	 * to this mapping might have occurred.
	 * 
	 * @return a map of names to projects with guaranteed O(1) performance on
	 * repeated read access. The first invocation might run in O(n), where n
	 * is the number of Projects defined in Jenkins.
	 * 
	 * @deprecated Do not use this function anymore, as its caching is
	 * somewhat unreliable in certain situations and it might cause deadlocks
	 * as it iterates over all items registered in Jenkins.
	 */
	@Deprecated
	public static Map<String, InheritanceProject> getProjectsMap() {
		Object obj = onChangeBuffer.get(null, "getProjectsMap");
		if (obj != null && obj instanceof Map) {
			return (Map) obj;
		}
		
		HashMap<String, InheritanceProject> pMap =
				new HashMap<String, InheritanceProject>();
		for (InheritanceProject p : Jenkins.get().getAllItems(InheritanceProject.class)) {
			if (p == null) { continue; }
			pMap.put(p.getFullName(), p);
		}
		
		onChangeBuffer.set(null, "getProjectsMap", pMap);
		return pMap;
	}
	
	/**
	 * Simple wrapper around {@link Jenkins#getItemByFullName(String, Class)}.
	 * <p>
	 * The class is set to {@link InheritanceProject}, of course. 
	 * 
	 * @param name the <b>full</b> name of the item. May be null.
	 * @return the project, if found, otherwise null.
	 */
	public static @CheckForNull InheritanceProject getProjectByName(String name) {
		if (StringUtils.isEmpty(name)) { return null; }
		return Jenkins.get().getItemByFullName(
				name, InheritanceProject.class
		);
	}
	
	public static void createBuffers() {
		if (onChangeBuffer == null) {
			onChangeBuffer = new TimedBuffer<InheritanceProject, String>();
		}
		if (onSelfChangeBuffer == null) {
			onSelfChangeBuffer = new TimedBuffer<InheritanceProject, String>();
		}
		if (onInheritChangeBuffer == null) {
			onInheritChangeBuffer = new TimedBuffer<InheritanceProject, String>();
		}
	}
	
	public static void clearBuffers(InheritanceProject root) {
		//Ensuring that the buffers are present
		createBuffers();
		
		if (root == null) {
			//Nuke all
			onChangeBuffer.clearAll();
			onSelfChangeBuffer.clearAll();
			onInheritChangeBuffer.clearAll();
			return;
		}
		
		//First clearing the cross-project change buffer
		onChangeBuffer.clearAll();
		//Then clearing the self-change buffer
		onSelfChangeBuffer.clear(root);
		
		//Then we need to clear the inheritable changes for the root and its children
		//Do note that the root MUST be cleared first, as otherwise we may
		//fetch an "unclean" relationship set
		onInheritChangeBuffer.clear(root);
		Map<InheritanceProject, Relationship> relMap = root.getRelationships();
		for (Map.Entry<InheritanceProject, Relationship> e : relMap.entrySet()) {
			//We ignore siblings
			if (e.getValue().type == Relationship.Type.MATE) {
				continue;
			}
			//Otherwise, we clear that project's inheritance buffer
			onInheritChangeBuffer.clear(e.getKey());
		}
	}
	
	
	
	// === PROJECT CONFIGURATION METHODS ===
	
	@Override
	public void doConfigSubmit(StaplerRequest req,
			StaplerResponse rsp) throws IOException, ServletException, FormException {
		//Check if we're transient; in which case a submit does nothing
		if (this.isTransient) {
			return;
		}
		
		//Calling the super implementation; will ultimately call submit()
		super.doConfigSubmit(req, rsp);
	}
	
	/**
	 * This method evaluates the form request created by the Descriptor and
	 * adjusts the properties of this project accordingly.
	 */
	@Override
	protected void submit(StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException, FormException {
		//Check if we're transient; in which case a submit does nothing
		if (this.isTransient) {
			return;
		}
		
		/* A submit might cause property changes across projects, and since
		 * the relationships between projects may change during a reconfigure,
		 * we need to nuke the buffers at three stages:
		 * 
		 * 1.) Before any change -- FULL NUKE
		 * 2.) Before saving versions -- LOCAL NUKE
		 * 3.) After saving versions -- LOCAL NUKE (to get version IDs right)
		 * 4.) After all changes applied -- FULL NUKE
		 */
		clearBuffers(this);
		
		/* Apply the configuration inherited from the superclass.
		 * Do note that the behaviour of that function might change erratically
		 * with each new Jenkins version.
		 * 
		 * One such change is that -- starting with v1.492 -- the BuildWrappers,
		 * Builders and Publisher fields are changed in-place instead of
		 * reassigned. This broke versioning as that causes new fields to be
		 * returned on each call; so that no in-place change can ever work.
		 */
		super.submit(req, rsp);
		
		JSONObject json = req.getSubmittedForm();
		
		if (json.has("isAbstract")) {
			this.isAbstract = json.getBoolean("isAbstract");
		} else {
			this.isAbstract = false;
		}
		
		if (json.has("projects")) {
			Object obj = json.get("projects");
			List<AbstractProjectReference> refs =
				AbstractProjectReference
					.ProjectReferenceDescriptor
					.newInstancesFromHeteroList(
							req, obj, AbstractProjectReference.all()
					);
			if (this.parentReferences != null) {
				this.parentReferences.clear();
			} else {
				this.parentReferences = new LinkedList<AbstractProjectReference>();
			}
			this.parentReferences.addAll(refs);
		} else {
			if (this.parentReferences != null) {
				this.parentReferences.clear();
			}
		}
		
		if(req.hasParameter("parameterizedWorkspace")) {
			this.parameterizedWorkspace = Util.fixEmptyAndTrim(
					req.getParameter("parameterizedWorkspace.directory")
			);
			
			if(this.parameterizedWorkspace != null) {
				//Normalize input to Unicode Form NKFC. See: http://www.unicode.org/reports/tr36/
				this.parameterizedWorkspace = Normalizer.normalize(this.parameterizedWorkspace, Form.NFKC);
			};
		} else {
			this.parameterizedWorkspace = null;
		}
		
		//Read the class of this project for listing and derivation purposes
		if (json.has("creationClass")) {
			this.creationClass = json.getString("creationClass");
		} else {
			this.creationClass = null;
		}
		
		//LOCAL NUKE before versioning is saved
		clearBuffers(this);
		
		//After everything was altered, we generate a new version
		if (json.has("versionMessageString")) {
			this.dumpConfigToNewVersion(json.getString("versionMessageString"));
		} else {
			this.dumpConfigToNewVersion();
		}
		
		//LOCAL NUKE after versioning is saved
		clearBuffers(this);
		
		
		//Note: ItemListener.onChanged() is called by caller (usually doConfigSubmit())
	}
	
	@Override
	public void updateByXml(Source source) throws IOException {
		//Check if the job is a transient job; in which case this must fail
		if (this.getIsTransient()) {
			String msg = String.format(
					"Updating %s by XML upload is not allowed: Transient project",
					this.getFullName()
			);
			log.warning(msg);
			throw new IOException(msg);
		}
		
		//Instruct the parent to update us
		super.updateByXml(source);
		//Then, save a new version
		
		clearBuffers(this);
		this.dumpConfigToNewVersion("New version uploaded as XML via API/CLI");
		clearBuffers(this);
		
		//Notify that this project may have changed
		ItemListener.fireOnUpdated(this);
	}
	
	@RequirePOST
	public synchronized void doSubmitChildJobCreation(
			StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException, FormException {
		//Check if we're transient; in which case a submit does nothing
		if (this.isTransient) {
			return;
		}
		
		JSONObject json = req.getSubmittedForm();
		
		// FULL NUKE before configuration change
		clearBuffers(null);
		
		//Decode the new properties
		if (json.has("properties")) {
			
			//Saving the old properties; except the parameter props and
			//removing them all from the current list
			List<JobProperty<? super InheritanceProject>> oldProps =
					new LinkedList<JobProperty<? super InheritanceProject>>();
			for (JobProperty jobProperty : this.properties) {
				if (!(jobProperty instanceof ParametersDefinitionProperty)) {
					oldProps.add(jobProperty);
				}
			}
			
			//Then, we read the new list from the JSON submission
			DescribableList<JobProperty<?>, JobPropertyDescriptor> newProps =
					new DescribableList<JobProperty<?>, JobPropertyDescriptor>(NOOP);
			newProps.rebuild(
					req,
					json.optJSONObject("properties"),
					JobPropertyDescriptor.getPropertyDescriptors(this.getClass())
			);
			
			//Then, we nuke the list, and add the rebuilt ones
			properties.clear();
			for (JobProperty p : newProps) {
				//Must use this.addProperty() to set correct owner
				this.addProperty(p);
			}
			
			//Finally, add the old properties; we don't need to call
			//this.addProperty(), because the old properties should already be
			//owned by this project
			this.properties.addAll(oldProps);
		}
		
		//Read the compatible projects
		if (json.has("compatibleProjects")) {
			Object obj = json.get("compatibleProjects");
			List<AbstractProjectReference> refs =
					AbstractProjectReference
						.ProjectReferenceDescriptor
						.newInstancesFromHeteroList(
								req, obj, AbstractProjectReference.all()
						);
			if (this.compatibleProjects != null) {
				this.compatibleProjects.clear();
			} else {
				this.compatibleProjects = new LinkedList<AbstractProjectReference>();
			}
			this.compatibleProjects.addAll(refs);
		} else {
			if (this.compatibleProjects != null) {
				this.compatibleProjects.clear();
			}
		}
		
		// FULL NUKE after configuration change
		clearBuffers(null);
		
		//Save data and send the redirect
		this.save();
		
		rsp.sendRedirect(this.getAbsoluteUrl());
		
		//After everything was altered, we generate a new version
		if (json.has("versionMessageString")) {
			this.dumpConfigToNewVersion(json.getString("versionMessageString"));
		} else {
			this.dumpConfigToNewVersion();
		}
		
		//LOCAL NUKE after versioning is saved
		clearBuffers(this);
		
		//Notify that this project may have changed
		ItemListener.fireOnUpdated(this);
	}
	
	
	@Override
	public void renameTo(String newName) throws IOException {
		if (this.name.equals(newName)) {
			return;
		}
		
		//Check if the user has the permission to rename transient projects
		//Do note that currently, that is impossible via the GUI for everyone anyway
		if (this.getIsTransient() &&
				!ProjectCreationEngine.instance.currentUserMayRename()) {
			throw new IOException(
					"Current user is not allowed to rename transient projects"
			);
		}
		
		//Recording our old project name
		String oldName = this.name;
		
		//Executing the rename
		super.renameTo(newName);
		
		//This means, that we need to force a refresh various buffers
		clearBuffers(this);
		
		//And then fixing all named references
		for (InheritanceProject p : getProjectsMap().values()) {
			//Define boolean to check whether the project is modified by the rename
			boolean modified = false;
			
			//Change all versions in the project itself (ignoring versions)
			for (AbstractProjectReference ref : p.getRawParentReferences()) {
				if (!ref.getName().equals(oldName)) { continue; }
				ref.switchProject(this);
				modified = true;
			}
			
			//Do the same for the compatible projects for automatic job creation (ignoring versions)
			for (AbstractProjectReference ref : p.compatibleProjects) {
				if (!ref.getName().equals(oldName)) { continue; }
				ref.switchProject(this);
				modified = true;
			}
			
			//Save if modified
			if (modified) { p.save(); }
			//Do not reset modified because change in object -> change in version
			
			//Change references in the version store also
			VersionedObjectStore verStore = p.getVersionedObjectStore();
			if (verStore == null) { continue; }
			
			//Change for parent references
			modified |= changeVersionedProjectReferences(
					verStore, "parentReferences",oldName, newName
			);
			
			//Change for compatible projects
			modified |= changeVersionedProjectReferences(
					verStore, "compatibleProjects",oldName, newName
			);
			
			//Save the changed versionStore, if needed
			if (modified) {
				p.saveVersionedObjectStore();
			}
		}
	}
	
	private boolean changeVersionedProjectReferences(
			VersionedObjectStore verStore,
			String keyInVersionStore,
			String oldName,
			String newName
	) {
		boolean modified = false;
		
		//Iterate over all versions
		Iterator it = verStore.getAllVersions().iterator();
		while (it.hasNext()) {
			Version v = (Version) it.next();
			
			//Get the old references
			LinkedList<AbstractProjectReference> referencesInVersionStore =
					(LinkedList)verStore.getObject(v, keyInVersionStore);
			
			//Loop through the parent references and change to new parent reference
			for (AbstractProjectReference ref : referencesInVersionStore) {
				if (!ref.getName().equals(oldName)) { continue; }
				ref.switchProject(newName);
				modified = true;
			}
		}
		return modified;
	}
	
	/**
	 * Adds the given {@link ProjectReference} as a parent to this node.
	 * <p>
	 * TODO: The fact that this function is public is really nasty.
	 * Basically, references should only be used through the validated
	 * frontend, or set by the equally validated {@link ProjectCreationEngine}.
	 * <p>
	 * Of course, since the user can just scribble around in the XML -- if
	 * the job isn't transient -- we can't prevent broken references
	 * anyway.
	 * <p>
	 * Do note that this change will not trigger any versioning or saving to
	 * disk. If you use this, you need to know exactly what you're doing; for
	 * example calling this in proper UnitTests.
	 * 
	 * @param ref the reference to add
	 * @param duplicateCheck if set to false, no duplication check shall be done.
	 * 		This is only useful in Unit-tests and nowhere else.
	 */
	public void addParentReference(AbstractProjectReference ref, boolean duplicateCheck) {
		//Checking if we already have such a reference
		if (duplicateCheck) {
			for (AbstractProjectReference ourRef : this.getParentReferences()) {
				if (ourRef.getName().equals(ref.getName())) {
					//No point in duplicated references
					return;
				}
			}
		}
		//Otherwise, we can add it. Of course, it might still lead to circular
		//references, or simply and plainly not exist
		this.parentReferences.push(ref);
		
		//And invalidating all caches
		clearBuffers(this);
	}
	
	/**
	 * Wrapper around
	 * {@link #addParentReference(AbstractProjectReference, boolean)} with
	 * duplication check enabled.
	 * 
	 * @param ref the references to add as a parent.
	 */
	public void addParentReference(AbstractProjectReference ref) {
		this.addParentReference(ref, true);
	}
	
	/**
	 * Removes a parent reference.
	 * <p>
	 * Same caveats apply as for {@link #addParentReference(AbstractProjectReference)}.
	 * 
	 * @param name the name of the project for which to remove one parent reference.
	 * @return true, if a parent reference was removed.
	 */
	public boolean removeParentReference(String name) {
		Iterator<AbstractProjectReference> iter = this.parentReferences.iterator();
		while (iter.hasNext()) {
			AbstractProjectReference apr = iter.next();
			if (apr.getName().equals(name)) {
				iter.remove();
				clearBuffers(this);
				return true;
			}
		}
		return false;
	}
	
	public void setVarianceLabel(String variance) {
		if (this.isTransient && StringUtils.isNotBlank(variance)) {
			this.variance = variance.trim();
		}
	}
	
	public void setAssignedLabel(Label l) throws IOException {
		super.setAssignedLabel(l);
		//After a label change, the caches need to be invalidated
		clearBuffers(this);
	}
	
	public void setCreationClass(String creationClass) {
		//Checking if such a class exists at all
		if (creationClass == null) { return; }
		for (CreationClass cc : ProjectCreationEngine.instance.getCreationClasses()) {
			if (cc.name.equals(creationClass)) {
				this.creationClass = creationClass;
				break;
			}
		}
	}
	
	/**
	 * This method is called after a save to restructure the dependency graph.
	 * The triggering method is
	 * {@link #doConfigSubmit(StaplerRequest, StaplerResponse)}.
	 * <p>
	 * Before most methods in the super-class were de-synchronized, this
	 * method was dangerous, because two separate projects would trigger a
	 * graph rebuild and get stuck on each other.
	 * <p>
	 * As such, a global lock for all projects was added here. Since most
	 * methods are de-synchronized now, this method ought to be re-entrant save.
	 * <p>
	 * On the other hand, the global locking does not cause much loss of
	 * performance, since graph rebuilding only happens on saving of jobs.
	 * <p>
	 * Therefore, for the moment the global lock is still fine.
	 */
	@Override
	protected void buildDependencyGraph(DependencyGraph graph) {
		//Fetch the global lock of all projects
		//TODO: Use an interruptible lock here?
		globalGraphBuildingLock.lock();
		try {
			super.buildDependencyGraph(graph);
		} finally {
			globalGraphBuildingLock.unlock();
		}
	}
	
	
	// === SAVING/LOADING METHODS ===
	
	/**
	 * This method serializes this object to offline storage. The default
	 * implementation of Jenkins is XML-File based, but that can be
	 * overridden herein. Of course, if you override the saving method,
	 * you will also have to override the loading method from
	 * {@link InheritanceProject.DescriptorImpl}.
	 */
	@Override
	public synchronized void save() throws IOException {
		//Checking if we're marked as transient; which causes no saving to occur
		if (this.isTransient) { return; }
		
		//Invoking the super constructor to save use
		super.save();
		//TODO: Save the version store to disk here
	}
	
	/**
	 * This method restores transient fields that could not be deserialized.
	 * Do note that there is no guaranteed order of deserialization, so
	 * don't expect other objects to be present, when this method is called.
	 */
	@Override
	public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
		//Creating & clearing buffers, if necessary
		createBuffers();
		clearBuffers(null);
		
		/* We need to create a dummy version store first, as we can't get the
		 * project root directory before super() is executed (as no name is
		 * set yet); but that one needs a version store available to load
		 * certain values reliably without a null pointer access.
		 */
		this.versionStore = new VersionedObjectStore();
		
		//Then loading the elements defined in the parent
		//TODO: What to do if a transient job is attempted to be loaded?
		super.onLoad(parent, name);
		
		//Loading the correct version store
		this.versionStore = this.loadVersionedObjectStore();
		
		//And clearing the buffers again, as a new job with new props is available
		clearBuffers(null);
	}
	
	public void onCopiedFrom(Item src) {
		//Do whatever the super-classes need to do
		super.onCopiedFrom(src);
		
		//Escape if someone abused this method
		if (!(src instanceof InheritanceProject)) { return; }
		InheritanceProject ip = (InheritanceProject) src;
		
		//Make sure that the new properties mirror the current stable version
		// -- not the one that was created last.
		if (ip.getLatestVersion() == ip.getStableVersion()) {
			//The stable and config.xml version are identical
			//Save a new version, marking from which job this one was copied
			this.dumpConfigToNewVersion(String.format(
					"Copied from: %s", ip.getFullName()
			));
			//Then just return, as no further job modification is needed
			return;
		}
		
		//Copy the versioned field from the other job's currently selected (stable) version
		try {
			this.copyVersionedSettingsFrom(ip);
		} catch (IOException ex) {
			log.severe(String.format(
					"Job '%s' could not be copied cleanly. Reason: %s",
					this.getFullName(),
					ex.getMessage()
			));
		}
		
		//Save a new version, marking from which job this one was copied
		this.dumpConfigToNewVersion(String.format(
				"Copied from: %s", ip.getFullName()
		));
	}
	
	/**
	 * This method tells this class and all its superclass's which directory
	 * to use for storing stuff.
	 * <p>
	 * For regular jobs this is the default Jenkins path for jobs ([root]/jobs).
	 * For transient jobs; this is redirected to ([root]/transient_jobs) to
	 * make them more invisible to Jenkins.
	 * <p>
	 * Note: Dynamically created project always exist in the root namespace.
	 */
	@Override
	public File getRootDir() {
		if (!this.isTransient) {
			return super.getRootDir();
		}
		File standardRoot = this.getParent().getRootDir();
		//Otherwise, we alter the last path segment
		String pathSafeJobName = this.getFullName().replaceAll("[/\\\\]", "_");
		File newRoot = new File(
				standardRoot.getAbsolutePath() +
				File.separator + "transient_jobs" + File.separator +
				pathSafeJobName
		);
		return newRoot;
	}
	
	protected File getVersionFile() {
		//Transient jobs do not have a concept of versions
		if (this.isTransient) {
			return null;
		}
		//TODO: This somewhat assumes, that the file will be compressed
		return new File(this.getRootDir(), "versions.xml.gz");
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public File getBuildDir() {
		return super.getBuildDir();
	}
	
	/**
	 * This method sets all the versioned fields in the local job to the
	 * values of the other job.
	 * <p>
	 * It uses the currently stored versioning information to select the right
	 * version of the source.
	 * <p>
	 * This field is private, because there is really only two internal methods
	 * that should make use of this:
	 * <ul>
	 * 	<li>{@link #onLoad(ItemGroup, String)}.</li>
	 * 	<li>{@link #doConfigDotXml(StaplerRequest, StaplerResponse)}</li>
	 * </ul>
	 * <p>
	 * Note: One field can't be altered, namely the "actions" field. That one
	 * is hidden by the parent class and can't be overwritten easily.
	 * 
	 * @param src the job to copy settings from.
	 * @throws IOException if the copying failed -- may leave the current
	 * 		project in an undefined state of some fields having been copied and
	 * 		otherse not.
	 */
	private void copyVersionedSettingsFrom(InheritanceProject src) throws IOException {
		if (src == null) { return; }
		
		//Parameterized workspace
		this.parameterizedWorkspace = src.getParameterizedWorkspace(IMode.LOCAL_ONLY);
		//Custom workspace
		this.setCustomWorkspace(src.getCustomWorkspace());
		//Quiet period
		this.setQuietPeriod(src.getQuietPeriodObject());
		//SCM Checkout Retry
		this.setScmCheckoutStrategy(src.getScmCheckoutStrategy(IMode.LOCAL_ONLY));
		
		//Parents
		this.parentReferences.clear();
		this.parentReferences.addAll(src.getParentReferences()); //Always LOCAL_ONLY
		
		//Properties
		this.properties.replaceBy(src.getAllProperties(IMode.LOCAL_ONLY));
		
		//Publishers
		this.getRawPublishersList().replaceBy(src.getPublishersList(IMode.LOCAL_ONLY));
		
		//Log Rotator
		this.setBuildDiscarder(src.getBuildDiscarder(IMode.LOCAL_ONLY));
		
		// Block Build
		this.blockBuildWhenDownstreamBuilding = src.blockBuildWhenDownstreamBuilding(IMode.LOCAL_ONLY);
		this.blockBuildWhenUpstreamBuilding = src.blockBuildWhenUpstreamBuilding(IMode.LOCAL_ONLY);
		
		//Build wrappers
		this.getRawBuildWrappersList().replaceBy(src.getBuildWrappersList(IMode.LOCAL_ONLY));
		
		//Compatible projects
		this.compatibleProjects.clear();
		this.compatibleProjects.addAll(src.getCompatibleProjects()); //Always LOCAL_ONLY
		
		//Build steps
		this.getRawBuildersList().replaceBy(src.getBuildersList(IMode.LOCAL_ONLY));
		
		//SCM
		this.setScm(src.getScm(IMode.LOCAL_ONLY));
	}
	
	
	
	// === URL-BOUND ACTION METHODS ===
	
	/**
	 * Accepts <tt>config.xml</tt> submission, as well as serve it.
	 */
	@Override
	@WebMethod(name = "config.xml")
	public void doConfigDotXml(StaplerRequest req, StaplerResponse rsp)
			throws IOException {
		/* Job reconfiguration is handled by the superclass, as there is no
		 * need to involve versioning/inheritance there 
		 */
		if (req.getMethod().equals("POST")) {
			super.doConfigDotXml(req, rsp);
		}
		if (!req.getMethod().equals("GET")) {
			//Huh? Only GET/POST make sense
			rsp.sendError(SC_BAD_REQUEST);
		}
		
		//Check that the calling user is allowed to read the config
		checkPermission(EXTENDED_READ);
		
		//Fetch the user-selected version (defaults to stable version)
		//Note: Transient projects have no config.xml and can't use super.dCDX()
		if (!this.isTransient) {
			Long latestVersion = this.getLatestVersion();
			Long selectedVersion = VersionHandler.getVersion(this);
			if (selectedVersion == null || selectedVersion == latestVersion) {
				//The super-method can handle these cases just fine
				super.doConfigDotXml(req, rsp);
				return;
			}
		}
		
		try {
			// Tell the client browser to expect XML
			rsp.setContentType("application/xml");
			// Send the modified XML to the user
			this.writeStableConfigDotXml(rsp.getOutputStream());
		} catch (HttpStatusException ex) {
			rsp.sendError(ex.status, ex.getMessage());
			return;
		}
	}
	
	protected void writeStableConfigDotXml(OutputStream out)
			throws IOException, HttpStatusException {
		if (out == null) { return; }
		//Get the XML for the current project, as it'd be written to disk
		String selfXml = Items.XSTREAM2.toXML(this);
		//Then, loading it back, to get a modifiable copy
		Object obj = Items.XSTREAM2.fromXML(selfXml);
		if (!(obj instanceof InheritanceProject)) {
			throw new HttpStatusException(
					SC_INTERNAL_SERVER_ERROR,
					"Error, could not (de-)serialize project"
			);
		}
		
		//Casting the project suitably, to access the needed fields
		InheritanceProject mod = (InheritanceProject) obj;
		
		/* Some alterations to the project wish to save it, for this to work, we
		 * need an ephemeral ItemGroup that serves as the container that can be
		 * cleaned, once it is not needed anymore
		 */
		MockItemGroup<Job<?,?>> mig = new MockItemGroup<>();
		
		try {
			//Giving the project a custom name and temporary item-group/directory
			UUID uuid = UUID.randomUUID();
			mod.onLoad(mig, uuid.toString());
			
			//Loaded project start without a VOB, so a blank one is created
			mod.versionStore = new VersionedObjectStore();
			
			//Replacing all versioned fields in the copy with the currently selected versions
			//NOTE: Actions can't be modified, since the parent class hides the field :(
			try {
				mod.copyVersionedSettingsFrom(this);
			} catch (IOException ex) {
				throw new HttpStatusException(
						SC_INTERNAL_SERVER_ERROR,
						"Failed to produce XML for stable version", ex
				);
			}
			
			// Turning this modified project into an UTF8 XML and sending it to the client
			Items.XSTREAM2.toXMLUTF8(mod, out);
		} finally {
			//Purge the temporary item group with fire
			mig.clean();
			//Delete all caches created for this object
			onChangeBuffer.clear(mod);
			onInheritChangeBuffer.clear(mod);
			onSelfChangeBuffer.clear(mod);
			
			//And purge the inheritance buffer for "this" too, since the page
			//might've been called with a "version" flag which breaks stuff
			onInheritChangeBuffer.clear(this);
		}
	}
	
	/**
	 * This method displays the configuration as a complete XML dump.
	 * 
	 * @param req the user-request
	 * @param rsp the response sent as a reply
	 * @return raw XML string
	 */
	public String doGetConfigAsXML(StaplerRequest req, StaplerResponse rsp) {
		//Check if the user only wants the local data
		String depth = req.getParameter("depth");
		int iDepth = 0;
		if (depth != null && !depth.isEmpty()) {
			try {
				iDepth = Integer.valueOf(depth);
			} catch (NumberFormatException ex) { }
		}
		if (iDepth <= 0) {
			Object obj = onSelfChangeBuffer.get(this, "doGetConfigAsXML");
			if (obj != null && obj instanceof String) {
				return (String) obj;
			}
			String str = Jenkins.XSTREAM2.toXML(this);
			onSelfChangeBuffer.set(this, "doGetConfigAsXML", str);
			return str;
		} else {
			Map<String, InheritanceProject> projs = new LinkedHashMap();
			for (AbstractProjectReference apr : this.getAllParentReferences(SELECTOR.BUILDER)) {
				InheritanceProject ip = apr.getProject();
				if (ip == null) { continue; }
				projs.put(ip.getFullName(), ip);
			}
			//Adding ourselves last
			projs.put(this.getFullName(), this);
			return Jenkins.XSTREAM2.toXML(projs);
		}
	}
	
	/**
	 * This method dumps the full expansion of all parameters (even derived
	 * ones) based on their default values into an XML file.
	 * <p>
	 * If you only want the default values of the last definition of each
	 * parameter, use {@link #doGetParamDefaultsAsXML()}
	 * 
	 * @return raw XML string
	 */
	public String doGetParamExpansionsAsXML() {
		/*
		Object obj = onInheritChangeBuffer.get(this, "doGetParamExpansionsAsXML");
		if (obj != null && obj instanceof String) {
			return (String) obj;
		}
		*/
		
		//Fetching a list of unique parameters
		List<ParameterDefinition> defLst = this.getParameters(IMode.INHERIT_FORCED);
		
		LinkedList<ParameterValue> valLst =
				new LinkedList<ParameterValue>();
		
		//Then, we fetch the expansion of these based on their defaults
		for (ParameterDefinition pd : defLst) {
			ParameterValue pv = null;
			if (pd instanceof InheritableStringParameterDefinition) {
				InheritableStringParameterDefinition ispd =
						(InheritableStringParameterDefinition) pd;
				pv = ispd.createValue(ispd.getDefaultValue());
			} else {
				pv = pd.getDefaultParameterValue();
			}
			if (pv != null) {
				valLst.add(pv);
			}
		}
		
		String str = Jenkins.XSTREAM2.toXML(valLst);
		//onInheritChangeBuffer.set(this, "doGetParamExpansionsAsXML", str);
		return str;
	}
	
	/**
	 * This method dumps the default values of all parameters (even derived
	 * ones) into an XML file.
	 * <p>
	 * Do note that this does not do any expansion,
	 * it merely outputs the last default value defined for the given
	 * parameter. If you want the full expansion, call
	 * {@link #doGetParamExpansionsAsXML()}
	 * 
	 * @return raw XML string
	 */
	public String doGetParamDefaultsAsXML() {
		Object obj = onInheritChangeBuffer.get(this, "doGetParamDefaultsAsXML");
		if (obj != null && obj instanceof String) {
			return (String) obj;
		}
		
		//Fetching a list of unique parameters
		List<ParameterDefinition> defLst =
				this.getParameters(IMode.INHERIT_FORCED);
		
		LinkedList<ParameterValue> valLst =
				new LinkedList<ParameterValue>();
		
		//Then, we fetch the expansion of these based on their defaults
		for (ParameterDefinition pd : defLst) {
			ParameterValue pv = pd.getDefaultParameterValue();
			if (pv != null) {
				valLst.add(pv);
			}
		}
		
		String str = Jenkins.XSTREAM2.toXML(valLst);
		onInheritChangeBuffer.set(this, "doGetParamDefaultsAsXML", str);
		return str;
	}
	
	/**
	 * This method dumps the version store as serialized XML.
	 * @return the versions as an XML file. May be empty, but never null.
	 */
	public String doGetVersionsAsXML() {
		if (this.versionStore == null) {
			return "";
		}
		return this.versionStore.toXML();
	}
	
	/**
	 * This method dumps the version store as serialized,
	 * GZIP compressed, Base64 encoded XML.
	 * @return the a Base64 encoded GZIP stream
	 */
	public String doGetVersionsAsCompressedXML() {
		if (this.versionStore == null) {
			return "";
		}
		String xml = this.versionStore.toXML();
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
			BASE64EncoderStream b64s = new BASE64EncoderStream(baos);
			GZIPOutputStream gos = new GZIPOutputStream(b64s);
			gos.write(xml.getBytes());
			gos.finish();
			gos.close();
			return baos.toString();
		} catch (IOException ex) {
			return "";
		}
		
	}
	
	@Override
	public void doDoDelete(StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException, InterruptedException {
		//Checking if this project is still referenced by another project
		for (Relationship rel : this.getRelationships().values()) {
			if (rel.type == Type.CHILD || rel.type == Type.MATE) {
				//Abort and redirect to error page
				rsp.sendRedirect(
					this.getAbsoluteUrl() + "/showReferencedBy"
				);
				return;
			}
		}
		
		/* If we reach this spot, we can safely delete the project
		 * 
		 * NOTE: This used to be a deadlock-trap, because #getPublishersList()
		 * used to be synchronized but could be called while a Queue lock was
		 * held.
		 * This collided with this function acquiring the object monitor first,
		 * before locking the Queue.
		 * 
		 * Between Jenkins 1.509 and 1.625, the requirement for synchronized
		 * methods in the Project class has been dropped, removing this deadlock. 
		 */
		super.doDoDelete(req, rsp);
		
		//Then, we refresh the project map and buffers
		clearBuffers(this);
	}
	
	public String doComputeVersionDiff(StaplerRequest req, StaplerResponse rsp) {
		//Checking if the two necessary parameters are set
		if (!req.hasParameter("l") || !req.hasParameter("r")) {
			return "<span style=\"color:red\"><b>No left/right version selected!</b></span>";
		}
		
		Long l = null;
		Long r = null;
		String mode = "unified";
		try {
			l = Long.parseLong(req.getParameter("l"), 10);
			r = Long.parseLong(req.getParameter("r"), 10);
			if (req.hasParameter("mode")) {
				mode = req.getParameter("mode");
			}
		} catch (NumberFormatException ex) {
			return "<span style=\"color:red\"><b>Left/right version is not a number!</b></span>";
		}
		
		//Fetch the value maps of both versions
		Map<String, Object> lMap = this.versionStore.getValueMapFor(l);
		if (lMap == null) {
			return "<span style=\"color:red\"><b>Left version does not exist!</b></span>";
		}
		
		Map<String, Object> rMap = this.versionStore.getValueMapFor(r);
		if (rMap == null) {
			return "<span style=\"color:red\"><b>Right version does not exist!</b></span>";
		}
		
		//Turning them into escaped XML
		String lXml = Jenkins.XSTREAM2.toXML(lMap);
		String rXml = Jenkins.XSTREAM2.toXML(rMap);
		
		StringBuilder b = new StringBuilder();
		if (mode.equals("unified")) {
			return computeUnifiedDiff(
				5,
				new AbstractMap.SimpleEntry(l, lXml),
				new AbstractMap.SimpleEntry(r, rXml)
			);
		} else if (mode.equals("side")) {
			b.append("<span style=\"color:red\"><b>");
			b.append("Side-by-Side diff not yet implemented.");
			b.append("</b></span>");
		} else if (mode.equals("raw")) {
			return computeRawTable(
					new AbstractMap.SimpleEntry(l, lXml),
					new AbstractMap.SimpleEntry(r, rXml)
				);
		} else {
			b.append("<span style=\"color:red\"><b>");
			b.append("Select a valid diff mode: 'unified', 'side' (for side-by-side), or 'raw'.");
			b.append("</b></span>");
		}
		return b.toString();
	}
	
	public String warnUserOnUnstableVersions() {
		String warnMessage = null;
		if (this.isAbstract) {
			Deque<Version> stableVersions = getStableVersions();
			Long latestVersion = getLatestVersion();
			if (stableVersions.size() > 0) {
				if (!this.versionStore.getVersion(latestVersion).getStability()) {
					warnMessage = Messages.InheritanceProject_OlderVersionMarkedAsStable();
				}
			} else {
				warnMessage = Messages.InheritanceProject_NoVersionMarkedAsStable();
			}
		}
		return warnMessage;
	}

	/**
	 * @return the versioning notification based on the current user desired version.
	 * 
	 * @see VersionedObjectStore#getUserNotificationFor(Long)
	 */
	public VersionsNotification getCurrentVersionNotification() {
		return versionStore.getUserNotificationFor(
				VersionHandler.getVersion(this)
		);
	}
	
	
	// === DIFF COMPUTATION METHODS ===
	
	private static String escapeHTMLFull(String str) {
		return StringEscapeUtils.escapeHtml(str);
	}
	
	private String computeRawTable(Map.Entry<Long, String>... versions) {
		String headFmt =
				"<tr><th $c style=\"width:3em\">#</th><th $c>Version %d</th><th $c style=\"width:3em\">#</th><th $c>Version %d</th></tr>"
				.replace("$c", "class=\"mono\"");
		String rowFmt =
				"<tr><td $c>%d</td><td $c>%s</td><td $c>%d</td><td $c>%s</td></tr>"
				.replace("$c", "class=\"mono\"");
		
		StringBuilder b = new StringBuilder();
		
		//We print both files in a table next to each other
		b.append("<table frame=\"void\" rules=\"cols\" width=\"100%\"");
		b.append("class=\"mono\">");
		b.append(String.format(
				headFmt, versions[0].getKey(), versions[1].getKey()
		));
		
		final String[] lArr = versions[0].getValue().split("\n");
		final String[] rArr = versions[1].getValue().split("\n");
		String[] lines = new String[2];
		int max = Math.max(lArr.length, rArr.length);
		for (int i = 0; i < max; i++) {
			lines[0] = (i < lArr.length) ? escapeHTMLFull(lArr[i]) : "";
			lines[1] = (i < rArr.length) ? escapeHTMLFull(rArr[i]) : "";
			b.append(String.format(
					rowFmt, i, lines[0], i, lines[1]
			));
		}
		b.append("</table>");
		
		return b.toString();
	}
	
	private String computeUnifiedDiff(int context, Map.Entry<Long, String>... versions) {
		if (versions.length != 2) {
			throw new IllegalArgumentException("You must pass exactly two versions");
		}
		if (context < 0) {
			context = 0;
		}
		
		StringBuilder b = new StringBuilder();
		
		//Splitting texts along newlines
		List<String> lLst = Arrays.asList(versions[0].getValue().split("\n"));
		List<String> rLst = Arrays.asList(versions[1].getValue().split("\n"));
		
		//We use Google's diff utils to create the diff patch
		Patch p = DiffUtils.diff(lLst, rLst);
		
		//Then, we display a unified diff
		List<String> outLst = DiffUtils.generateUnifiedDiff(
				"Version " + versions[0].getKey(),
				"Version " + versions[1].getKey(),
				lLst, p, context
		);
		
		for (String line : outLst) {
			boolean hasColour = false;
			if (line.startsWith("++")) {
				b.append("<span style=\"color:orange\">");
				hasColour = true;
			} else if (line.startsWith("+")) {
				b.append("<span style=\"color:green\">");
				hasColour = true;
			} else if (line.startsWith("--")) {
				b.append("<span style=\"color:blue\">");
				hasColour = true;
			} else if (line.startsWith("-")) {
				b.append("<span style=\"color:red\">");
				hasColour = true;
			}
			String mod = escapeHTMLFull(line);
			b.append(mod);
			if (hasColour) {
				b.append("</span>");
			}
			b.append("<br>");
		}
		
		return b.toString();
	}
	
	
	
	// === BUILD STARTING METHODS ===
	
	/**
	 * This method may be used by projects extending from this class, to
	 * modify the build, shortly before it is passed over to scheduling.
	 * 
	 * @param req the user request
	 * @param rsp the response to return
	 * @throws IOException in case of error
	 * @throws ServletException in case of error
	 */
	protected void onBuild(StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException {
		//Does nothing
	}
	
	/**
	 * This method may be used by projects extending from this class, to
	 * modify the build, shortly before it is actually submitted into the Queue.
	 * 
	 * @param quietPeriod the number of seconds to delay the build. 
	 * @param c the cause for this build
	 * @param actions the actions added to the build
	 */
	protected void onScheduleBuild2(
			int quietPeriod, Cause c, Collection<? extends Action> actions) {
		//Does nothing
	}
	
	/**
	 * Executes a build started from the GUI.
	 * <p>
	 * Queries for parameters on an HTTP/GET, tries to decode submitted
	 * parameters on a HTTP/POST.
	 * <p>
	 * Before we can call the actual build, we must make sure that
	 * parameters are properly inherited; as the super implementation will
	 * NOT query {@link #isParameterized()}, but instead rely on querying
	 * whether the Project has a {@link ParametersDefinitionProperty} property.
	 * <p>
	 * As we need to treat Parameters created by ourselves different from
	 * those assigned by parents, we must override {@link #getProperty(Class)}
	 * to produce a suitable {@link ParametersDefinitionProperty} reference
	 * on the spot.
	 * <p>
	 * Do note that the {@link ParametersAction} objects that store the actual
	 * values will be created by
	 * {@link ParametersDefinitionProperty#_doBuild(StaplerRequest, StaplerResponse, TimeDuration)}
	 * later on. Also do note that we can't extend
	 * {@link ParametersDefinitionProperty} or {@link ParametersAction}, because
	 * {@link AbstractProject} only checks for exact class matches.
	 */
	@Override
	public void doBuild(StaplerRequest req, StaplerResponse rsp, @QueryParameter TimeDuration delay)
			throws IOException, ServletException {
		/* Purge the PERMANENT versioning information that might be left over
		 * from a previous run. The transient information (from the Request) is
		 * kept.
		 */
		VersionHandler.clearVersionsPartial();
		
		//Initialize versioning for active request from request / defaults
		VersionHandler.initVersions(this);
		try {
			this.doBuildInternal(req, rsp, delay);
		} finally {
			// Clean out ALL versioning data
			VersionHandler.clearVersions();
		}
	}
	
	/**
	 * This is called by {@link #doBuild(StaplerRequest, StaplerResponse, TimeDuration)}
	 * and implements the actual build scheduling and parameter assignment magic.
	 * <p>
	 * Do note that it expects, that all relevant versioning information has
	 * already been registered in the current request and / or thread via
	 * {@link VersionHandler#initVersions(AbstractProject)}.
	 * 
	 * @param req the request the user submitted.
	 * @param rsp the response to allow information to be passed to the user
	 * @param delay the build delay
	 * 
	 * @throws IOException
	 * @throws ServletException
	 */
	private void doBuildInternal(StaplerRequest req, StaplerResponse rsp, @QueryParameter TimeDuration delay)
			throws IOException, ServletException {
		//The delay parameter might be null in case somebody used a custom URL
		if (delay == null) {
			delay = new TimeDuration(0);
		}
		
		//Call the superclass to modify the job further
		this.onBuild(req, rsp);
		
		/* === START COPY OF SUPER FUNCTION ===
		 * 
		 * We can't call the super-function, as it does not allow us to
		 * add a custom action to rescue the versioning over to the start of
		 * the actual build.
		 */
		
		//TODO: Check if this ACL check actually does the same as the commented
		//instruction below
		//ACL acl = Jenkins.get().getACL();
		//acl.checkPermission(BUILD);
		super.checkPermission(BUILD);		
		//BuildAuthorizationToken.checkPermission(this, getAuthToken(), req, rsp);
		
		// if a build is parameterized, let that take over
		ParametersDefinitionProperty pp = getProperty(ParametersDefinitionProperty.class);
		if (pp != null) {
			//FIXME: The BuildViewExtension needs to be handled even when PP is a standard PDP
			
			//Determine whether to show the parameter entry form, or trigger a build
			if (!req.getMethod().equals("POST")) {
				// show the parameter entry form.
				req.getView(pp, "index.jelly").forward(req, rsp);
				return;
			} else {
				pp._doBuild(req, rsp, delay);
			}
			return;
		}
		
		// If this point is reached; the build is not parameterised
		
		if (!isBuildable()) {
			//Super implementation throws HTTP response; but we do not need the stacktrace
			rsp.sendError(
					SC_INTERNAL_SERVER_ERROR,
					String.format("%s is not buildable", this.getFullName())
			);
			return;
		}
		
		// Invoke the onBuild actions contributed by BuildViewExtension
		List<Action> actions = BuildViewExtension.callAll(this, req);
		//Add the versioning Action, with the CURRENTLY active versions
		actions.add(new VersioningAction(VersionHandler.getVersions()));
		//Add the cause action
		actions.add(this.getBuildCauseOverride(req));
		
		Jenkins.get().getQueue().schedule2(
				this, delay.getTime(), actions
		);
		
		//Send the user back, except if "rebuildNoRedirect" is set
		if (req.getAttribute("rebuildNoRedirect") == null) {
			rsp.forwardToPreviousPage(req);
		}
		// === END COPY OF SUPER FUNCTION ===
	}
	
	/**
	 * This method is used, when the user wishes to build a very specific
	 * version of this project or its parents.
	 * <p>
	 * This method comes in three stages:
	 * <ol>
	 * <li>The first that displays the current versions, and allows the user to
	 * select the correct ones.</li>
	 * <li>The second, that parses the version map from JSON and refreshes the page</li>
	 * <li>The third, where the user actually wants to start the build.</li>
	 * </ol>
	 * Once the third phase is reached; the versioning information is encoded
	 * in an URL parameter and passed to the normal build page and handled
	 * via {@link #doBuild(StaplerRequest, StaplerResponse, TimeDuration)}
	 * 
	 * @param req the incoming request from the user.
	 * @param rsp the response that shall be sent to the user
	 * 
	 * @throws IOException in case of saving issues
	 * @throws ServletException in case of server error
	 * @throws FormException in case of invalid input form data
	 */
	public void doBuildSpecificVersion(StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException, FormException {
		//Purge whatever's stored in the thread from a previous run
		//ThreadAssocStore.getInstance().clear(Thread.currentThread());
		
		//If we did not submit a form; just display the initial data
		if(!req.getMethod().equals("POST")) {
			req.getView(this, "buildSpecificVersion").forward(req,rsp);
			return;
		}
		
		//Convert the request's JSON into a proper Map
		Map<String, Long> verMap = VersionHandler.getFromFormRequest(req);
		if (verMap == null) {
			throw new FormException(
					"Could not decode versioning table",
					VersionHandler.VERSIONING_KEY
			);
		}
		
		//Now, filter the map, to allow an URL that is as short as possible
		this.filterVersionMap(verMap);
		
		//And turn the Map into an URL-safe string
		String verMapStr = VersionHandler.encodeUrlParameter(verMap);
		
		//TODO: Think about passing the verMapStr compressed
		String verUrlParm = VersionHandler.getFullUrlParameter(verMapStr);
		
		//Checking if the user wants to build or refresh the page
		if (req.hasParameter("doRefresh")) {
			//Refreshing the page with the newly selected versions
			rsp.sendRedirect("buildSpecificVersion?" + verUrlParm);
		} else {
			//Triggering a nice build with the given version map
			rsp.sendRedirect("build?" + verUrlParm);
		}
	}
	
	/**
	 * This method simplifies the given versioning map, by removing all versions
	 * that superfluous.
	 * <p>
	 * Superfluous versions are those:
	 * <ul>
	 * 	<li>which are the last stable version of a project,</li>
	 *  <li>the last version of a project without stable ones or</li>
	 * 	<li>the only version of a project.</li>
	 * </ul>
	 * All three cases lead to these versions to be the default, and thus
	 * unnecessary to be mentioned in GET/POST requests. This can substantially
	 * lower the length of these URL requests.
	 * 
	 * @param verMap the map to filter. Elements are removed in place.
	 */
	protected void filterVersionMap(Map<String, Long> verMap) {
		if (verMap == null) { return; }
		
		Set<String> removals = new HashSet<>();
		for (String pName : verMap.keySet()) {
			InheritanceProject ip = 
					Jenkins
					.getInstance()
					.getItemByFullName(pName, InheritanceProject.class);
			
			//Check for existence of the project
			if (ip == null) {
				//Not a known IP -- so no need to include it
				removals.add(pName);
				continue;
			}
			
			//Check if there's only one version at all
			if (ip.getVersionedObjectStore().getAllVersions().isEmpty()) {
				removals.add(pName);
				continue;
			}
			
			//Check if identical to last stable version (or last, if no stables)
			Long selectedVersion = verMap.get(pName);
			if (selectedVersion == null || selectedVersion < 1) {
				removals.add(pName);
				continue;
			}
			Long stableVersion = ip.getStableVersion();
			if (stableVersion != null && stableVersion.equals(selectedVersion)) {
				//Same as latest stable or latest version, if no stables
				removals.add(pName);
				continue;
			}
		}
		if (!removals.isEmpty()) {
			verMap.keySet().removeAll(removals);
		}
	}
	
	/**
	 * See: {@link #doBuildWithParameters(StaplerRequest, StaplerResponse, TimeDuration)}
	 */
	@Override
	public void doBuildWithParameters(StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException {
		//TODO: The below function did not have the TimeDuration param previously
		TimeDuration td = new TimeDuration(0);
		super.doBuildWithParameters(req, rsp, td);
	}
	
	/**
	 * {@inheritDoc}
	 * <p>
	 * <b>Do note:</b> This method is <i>not</i> calling the super-implementation,
	 * because it is not aware that the default values for parameters must be
	 * derived via inheritance, if the method is called directly (instead of
	 * via the CLI).
	 * 
	 * @see InheritableStringParameterDefinition#getDefaultParameterValue()
	 */
	@Override
	public QueueTaskFuture<InheritanceBuild> scheduleBuild2(
			int quietPeriod, Cause c, Collection<? extends Action> actions) {
		//Purge whatever's stored in this thread from a previous run
		VersionHandler.clearVersions();
		
		//Creating new list of actions, as we replace and merge them
		List<Action> oActions = new LinkedList<Action>();
		List<VersioningAction> vActions = new LinkedList<VersioningAction>();
		List<ParametersAction> pActions = new LinkedList<ParametersAction>();
		
		//Removing versioning and parameterActions from the input actions, to
		//treat them separately and unify them as one action each for the build
		for (Action a : actions) {
			if (a instanceof VersioningAction) {
				vActions.add((VersioningAction) a);
			} else if (a instanceof ParametersAction) {
				pActions.add((ParametersAction) a);
			} else {
				oActions.add(a);
			}
		}
		
		//Seeding the environment with versions passed into this build (if any)
		for (VersioningAction va : vActions) {
			//Storing that version-map in the thread
			VersionHandler.addVersions(va.versionMap);
		}
		
		//Appending parameter-based versioning overrides
		for (ParametersAction pa : pActions) {
			ParameterValue pv = pa.getParameter(
					ParameterSelector.VERSION_PARAM_NAME
			);
			if (pv == null || !(pv instanceof StringParameterValue)) {
				continue;
			}
			StringParameterValue spv = (StringParameterValue) pv;
			Map<String, Long> vMap = VersionHandler.decodeUrlParameter(spv.getValue().toString());
			if (vMap == null) { continue; }
			
			//The decoded map is registered in the thread
			VersionHandler.addVersions(vMap);
		}
		
		/* Create a new versioning action, with the now full set of versions
		 * retrieved either form:
		 *   - The URL request content
		 *   - The special versioning assignment parameter
		 *   - A VersioningAction passed into this build
		 *   - Or, if all else fails, the defaults of the current project
		 * 
		 * Note that this also adds projects back in, which only had 1 version
		 * to select from, since the build/rebuild UI will ignore those.
		 */
		Map<String, Long> vMap = VersionHandler.initVersions(this);
		oActions.add(new VersioningAction(vMap));
		
		
		//The buildable check must be done after versioning assignment
		if (!isBuildable()) { return null; }
		
		//Check if parameterisation is needed
		if (isParameterized()) {
			/* No matter who called us with what set of parameters, it
			 * absolutely MUST be ensured that all inherited parameters are
			 * present.
			 * This is because some plugins do not fetch the parameters of the
			 * project at all, or  in a way, which can't be detected as
			 * needing parameter inheritance (i.e. outside of builds, the queue,
			 * certain URLs, and so on;
			 * see: InheritanceGovernor.inheritanceLookupRequired())
			 * 
			 * These starts would produce an incomplete set of parameters.
			 * This incomplete list needs to be filled up.
			 */
			Map<String, ParameterValue> pvMap = new HashMap<>();
			
			//Fill in parameters from the user -- if any
			for (ParametersAction pa : pActions) {
				for (ParameterValue pv : pa.getParameters()) {
					if (pv == null || pv.getName() == null) { continue; }
					pvMap.put(pv.getName(), pv);
				}
			}
			
			//Fill in defaults from the project that have not yet been set
			for (ParameterDefinition def : this.getParameters()) {
				String pName = def.getName();
				//Ignore already assigned parameters
				if (pvMap.containsKey(pName)) { continue; }
				
				ParameterValue pv;
				if (def instanceof InheritableStringParameterDefinition) {
					InheritableStringParameterDefinition ispd =
							(InheritableStringParameterDefinition) def;
					pv = ispd.createValue(ispd.getDefaultValue());
				} else {
					pv = def.getDefaultParameterValue();
				}
				pvMap.put(pName, pv);
			}
			
			List<ParameterValue> pvLst = new LinkedList<>(pvMap.values());
			oActions.add(new ParametersAction(pvLst));
		} else {
			//Project not parameterized, but parameters given -- just pass along
			oActions.addAll(pActions);
		}
		
		//Add a Cause Action, if a cause was given
		if (c != null) {
			oActions.add(new CauseAction(c));
		}
		
		this.onScheduleBuild2(quietPeriod, c, oActions);
		
		ScheduleResult sched = Jenkins.get().getQueue().schedule2(
				this, quietPeriod, oActions
		);
		if (sched == null || sched.isRefused()) {
			return null;
		}
		return (QueueTaskFuture) sched.getItem().getFuture();
	}
	
	/**
	 * This is a copy (not a wrapper) of getBuildCause() in
	 * {@link AbstractProject}. This is necessary, because we can't access that
	 * field as our parent is loaded by a different class loader.
	 * <p>
	 * The function is used, because we need to splice-in one additional
	 * {@link Action} for creation of Builds: {@link VersioningAction}.
	 * <p>
	 * Additional {@link Cause} are also queried over the
	 * {@link BuildCauseOverride} extension interface.
	 * 
	 * @param req the incoming user request
	 * @return an altered {@link CauseAction} extended by suitable actions.
	 */
	@SuppressWarnings("deprecation")
	public CauseAction getBuildCauseOverride(StaplerRequest req) {
		List<Cause> causes = BuildCauseOverride.getBuildCauseOverrideByAll(req);

		if (getAuthToken() != null && getAuthToken().getToken() != null && req.getParameter("token") != null) {
			// Optional additional cause text when starting via token
			String causeText = req.getParameter("cause");
			causes.add(new RemoteCause(req.getRemoteAddr(), causeText));
		} else {
			Object rebuild = req.getAttribute("rebuildCause");
			if (rebuild != null && rebuild instanceof InheritanceRebuildAction) {
				InheritanceRebuildAction inheritanceRebuildAction = (InheritanceRebuildAction) rebuild;
				if (inheritanceRebuildAction.getBuild() != null) {
					causes.add(new RebuildCause(inheritanceRebuildAction.getBuild().number,
							inheritanceRebuildAction.getBuild().getUrl()));
				}
			} else {
				causes.add(new UserIdCause());
			}
		}

		return new CauseAction(causes);
	}

	
	// === VERSIONING HANDLING AND DERIVATION METHODS ===
	
	@RequirePOST
	public void doConfigVersionsSubmit(StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException, FormException {
		checkPermission(VERSION_CONFIG);
		
		class Entry {
			Long id;
			String desc;
			boolean stable;
			
			public Entry(Long id, String desc, boolean stable) {
				this.id = id;
				this.desc = desc;
				this.stable = stable;
			}
		}
		
		LinkedList<Entry> fields = new LinkedList<Entry>();
		
		try {
			//Decoding the form data to structured JSON
			JSONObject json = req.getSubmittedForm();
			
			//Checking if the JSON has all necessary fields
			String[] keys = { "versionID", "description", "stable" };
			for (String key : keys) {
				if (!json.has(key)) {
					log.warning("Got submission of broken version config form.");
					return;
				}
			}
			try {
				JSONArray vArr = json.getJSONArray("versionID");
				JSONArray dArr = json.getJSONArray("description");
				JSONArray sArr = json.getJSONArray("stable");
				//Sanity check
				if (vArr.size() != dArr.size() || vArr.size() != sArr.size()) {
					log.warning("Field in version config form differ in length.");
					return;
				}
				
				//Then, we decode each tuple and alter the version referenced
				for (int i = 0; i < vArr.size(); i++) {
					try {
						fields.add(
							new Entry(
								Long.valueOf(vArr.getString(i), 10),
								dArr.getString(i),
								sArr.getBoolean(i)
							)
						);
					} catch (JSONException ex) {
						log.warning("Invalid value in version config at index " + i);
					} catch (NumberFormatException ex) {
						log.warning("Invalid id in version config at index " + i);
					}
				}
			} catch (JSONException ex) {
				try {
					// One field in version config form was not an array; trying strings
					Long jv = Long.valueOf(json.getString("versionID"), 10);
					String jd = json.getString("description");
					Boolean js = json.getBoolean("stable");
					fields.add(new Entry(jv, jd, js));
				} catch (JSONException ex2) {
					log.warning("Invalid value in version config!");
					return;
				} catch (NumberFormatException ex2) {
					log.warning("Invalid id in version config!");
					return;
				}
			}
			
			//After having decoded the fields, we alter the versions appropriately
			for (Entry e : fields) {
				//Fetching version
				Version v = this.versionStore.getVersion(e.id);
				if (v == null) {
					log.warning("No such version " + e.id + " for " + this.getFullName());
					continue;
				}
				v.setStability(e.stable);
				v.setDescription(e.desc);
			}
			
			//Saving the altered versions to disk
			this.versionStore.save(this.getVersionFile());
			
			for (VersionChangeListener vcl : VersionChangeListener.all()) {
				vcl.onUpdated(this);
			}
			
			//The selection of the stable version might have changed. So we need
			//to clear the local buffers
			clearBuffers(this);
			
			//Fire the onChanged events, since switching versions may change fields
			ItemListener.fireOnUpdated(this);
			
			//At the end, we mark the forms as successfully submitted
			FormApply.success(".").generateResponse(req, rsp, null);
		} catch (JSONException e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			pw.println("Failed to parse form data. Please report this problem as a bug!");
			pw.println("JSON=" + req.getSubmittedForm());
			pw.println();
			e.printStackTrace(pw);
			
			rsp.setStatus(SC_BAD_REQUEST);
			sendError(sw.toString(), req, rsp, true);
		}
	}
	
	protected VersionedObjectStore loadVersionedObjectStore() {
		//TODO: This should read stuff from disk / DB
		File vFile = this.getVersionFile();
		if (vFile == null || !vFile.isFile()) {
			//Creating an empty VOS, in case none is stored anywhere
			return new VersionedObjectStore();
		}
		//Otherwise, we attempt to load it from disk
		VersionedObjectStore vos = null;
		try {
			vos = VersionedObjectStore.load(vFile);
		} catch (IOException ex) {
			log.warning(
					"No versions loaded for " + this.getFullName() + ". " +
					ex.getLocalizedMessage()
			);
			return new VersionedObjectStore();
		} catch (IllegalArgumentException ex) {
			log.warning(
					"No versions loaded for " + this.getFullName() + ". " +
					ex.getLocalizedMessage()
			);
			return new VersionedObjectStore();
		} catch (XStreamException ex) {
			log.warning(
					"Could not load Version for: " + this.getFullName() + ". " +
					ex.getLocalizedMessage()
			);
			return new VersionedObjectStore();
		}
		
		//Update that store -- regardless of the current object
		boolean wasModified = updateVersionedObjectStore(vos);
		if (wasModified) {
			try {
				vos.save(vFile);
			} catch (IOException ex) {
				log.severe(String.format(
						"Failed to save version to: %s; Reason = %s",
						this.getVersionFile(), ex.getMessage()
				));
			}
		}
		
		//Since we'll add/remove lots of properties, we put Jenkins in
		//bulk-change mode
		try (BulkChange bc = new BulkChange(this)) {
			//Then, we need to patch up certain fields in the store
			for (HashMap<String, Object> m : vos.getAllValueMaps()) {
				//The properties need to have their owner set, which happens by
				//adding & removing them
				Object obj = m.get("properties");
				if (obj != null && obj instanceof List) {
					List<JobProperty<Job<?,?>>> lst = (List<JobProperty<Job<?,?>>>) obj;
					for (JobProperty<Job<?,?>> prop : lst) {
						//Adding the property to us
						this.addProperty(prop);
						//And immediately removing the property
						this.removeProperty(prop);
					}
				}
			}
			bc.commit();
		} catch (IOException e) {
			//Nothing to do -- the BC will automatically abort
		}
			
		return vos;
	}
	
	private static boolean updateVersionedObjectStore(VersionedObjectStore vos) {
		if (vos == null) { return false; }
		boolean wasModified = false;
		
		//Check if the logRotator settings were correctly converted to Jenkins 2.x properties
		for (HashMap<String, Object> vm : vos.getAllValueMaps()) {
			//Check if the old-style setting is present at all
			if (!vm.containsKey("logRotator")) { continue; }
			//A log rotator setting is there, but might be null
			Object logRotator = vm.get("logRotator");
			if (!(logRotator instanceof BuildDiscarder)) {
				//It was null or invalid -- hence just drop it
				vm.remove("logRotator");
				wasModified = true;
				continue;
			}
			//There is an old-style setting -- making sure that it has been converted
			Object pObj = vm.get("properties");
			if (!(pObj instanceof List)) {
				//Creating a new properties list is needed
				LinkedList<JobProperty<? super InheritanceProject>> pLst =
						new LinkedList<>();
				pLst.add(new BuildDiscarderProperty((BuildDiscarder)logRotator));
			} else {
				List<JobProperty<? super InheritanceProject>> pLst =
						(List) pObj;
				boolean hasDiscarder = false;
				for (JobProperty<? super InheritanceProject> p : pLst) {
					if (p instanceof BuildDiscarderProperty) {
						hasDiscarder = true;
						break;
					}
				}
				if (!hasDiscarder) {
					pLst.add(new BuildDiscarderProperty((BuildDiscarder)logRotator));
				}
			}
			//In any case, the old log rotator setting has to be dropped
			vm.remove("logRotator");
			wasModified = true;
		}
		return wasModified;
	}
	
	
	/**
	 * Wrapper around {@link #dumpConfigToNewVersion(String)} with an empty
	 * message.
	 * <p>
	 * This method should only be used outside the package, to allow test cases
	 * to generate new versions for test purposes.<br>
	 * External non-test code should not have any need to call this.
	 */
	public synchronized void dumpConfigToNewVersion() {
		this.dumpConfigToNewVersion(null);
	}
	
	/**
	 * This method takes the current configuration and dumps all relevant
	 * fields into the versioning-store.
	 * <p>
	 * Do note that versioning is stored separately from inheritance, but
	 * evaluated together. This means that, over time, parentage may change as
	 * well as compatibility markings. These <b>all</b> need to be saved
	 * indefinitely.
	 * <p>
	 * To save custom fields in subclasses, override
	 * {@link #dumpConfigToVersion(Version)}.
	 * <p>
	 * This method should only be used outside the package, to allow test cases
	 * to generate new versions for test purposes.<br>
	 * External non-test code should not have any need to call this.
	 * 
	 * @param message the message to be used for the new version
	 */
	public synchronized void dumpConfigToNewVersion(String message) {
		//Sanity checks
		if (this.isTransient) { return; }
		if (this.versionStore == null) {
			this.versionStore = this.loadVersionedObjectStore();
		}
		
		/* ATTENTION! Do NOT save the lists themselves, but rather copy them,
		 * unless you know that you already have a copy. Due to the nature
		 * of how Jenkins saves data, you don't need to copy the stored objects
		 * themselves.
		 * 
		 * Also never, ever save inherited or versioned data. Only save
		 * whatever the "super" class believes to be true for the project or
		 * whatever is directly saved as a field in this class.
		 */
		//Creating the next, clean version
		Version v = this.versionStore.createNextVersionAsEmpty();
		
		//Fetching the currently logged-on user and assigning that to the version
		String username = Jenkins.getAuthentication().getName();
		if (username != null && !username.isEmpty()) {
			v.setUsername(username);
		}
		
		//Attach the message (if any)
		if (message != null) {
			v.setDescription(message);
		}
		
		this.dumpConfigToVersion(v);

		
		//Now, we check if this version is the same as the last one
		Version prev = this.versionStore.getVersion(v.id - 1);
		if (prev != null && this.versionStore.areIdentical(prev, v)) {
			//Drop the version, if possible
			this.versionStore.undoVersion(v);
		}
		//Save the file, to persist our changes
		try {
			this.versionStore.save(this.getVersionFile());
		} catch (IOException ex) {
			log.severe(String.format(
					"Failed to save version to: %s; Reason = %s",
					this.getVersionFile(), ex.getMessage()
			));
		}
	}
	
	/**
	 * This method implements the actual association of a set of objects with
	 * a given version.
	 * <p>
	 * Subclasses should override this method, call the super() implementation
	 * and then associate their own fields with that version by calling
	 * {@link VersionedObjectStore#setObjectFor(Version, String, Object)}.
	 * 
	 * @param v the version to archive settings for. Must never be null.
	 */
	protected void dumpConfigToVersion(Version v) {
		//Storing the list of parents
		this.versionStore.setObjectFor(
				v, "parentReferences",
				new LinkedList<AbstractProjectReference>(this.getRawParentReferences())
		);
		
		//Storing the list of compatibility matings -- also contains
		//the parameters defined on them.
		this.versionStore.setObjectFor(
				v, "compatibleProjects",
				new LinkedList<AbstractProjectReference>(this.compatibleProjects)
		);
		
		//Storing the properties of this job; this contains the project parameters
		this.versionStore.setObjectFor(
				v, "properties",
				new LinkedList<JobProperty<? super InheritanceProject>>(
						super.getAllProperties()
				)
		);
		
		//Storing build wrappers
		this.versionStore.setObjectFor(
				v, "buildWrappersList",
				new DescribableList<BuildWrapper, Descriptor<BuildWrapper>>(
						NOOP, super.getBuildWrappersList().toList()
				)
		);
		
		//Storing builders
		this.versionStore.setObjectFor(
				v, "buildersList",
				new DescribableList<Builder, Descriptor<Builder>>(
						NOOP, super.getBuildersList().toList()
				)
		);
		
		//Storing publishers
		this.versionStore.setObjectFor(
				v, "publishersList",
				new DescribableList<Publisher, Descriptor<Publisher>>(
						NOOP, super.getPublishersList().toList()
				)
		);
		
		//Storing actions
		this.versionStore.setObjectFor(
				v, "actions", new LinkedList<Action>(super.getActions())
		);
		
		
		//Storing the other, more simple properties
		this.versionStore.setObjectFor(v, "scm", super.getScm());
		this.versionStore.setObjectFor(v, "quietPeriod", this.getRawQuietPeriod());
		this.versionStore.setObjectFor(v, "scmCheckoutRetryCount", this.getRawScmCheckoutRetryCount());
		this.versionStore.setObjectFor(v, "scmCheckoutStrategy", super.getScmCheckoutStrategy());
		this.versionStore.setObjectFor(v, "blockBuildWhenDownstreamBuilding", super.blockBuildWhenDownstreamBuilding());
		this.versionStore.setObjectFor(v, "blockBuildWhenUpstreamBuilding", super.blockBuildWhenUpstreamBuilding());
		this.versionStore.setObjectFor(v, "customWorkspace", super.getCustomWorkspace());
		this.versionStore.setObjectFor(v, "parameterizedWorkspace", this.getRawParameterizedWorkspace());
	}
	
	
	public static InheritanceProject getProjectFromRequest(StaplerRequest req) {
		return req.findAncestorObject(InheritanceProject.class);
	}
	
	
	/**
	 * Returns the currently desired version in the request state as a String.
	 * <p>
	 * It is used to determine the content of the version selection WEB UI
	 * element.
	 * 
	 * @return the selected version, or the empty string, if no version available.
	 */
	public String getUserDesiredVersion() {
		Long v = VersionHandler.getVersion(this);
		if (v == null) {
			return "";
		} else {
			return v.toString();
		}
	}
	
	public Deque<Long> getVersionIDs() {
		Object obj = onSelfChangeBuffer.get(this, "getVersionIDs()");
		if (obj != null && obj instanceof Deque) {
			return (Deque) obj;
		}
		
		LinkedList<Long> lst = new LinkedList<Long>();
		for (Version v : this.getVersions()) {
			lst.add(v.id);
		}
		
		onSelfChangeBuffer.set(this, "getVersionIDs()", lst);
		return lst;
	}
	
	public Deque<Version> getVersions() {
		Object obj = onSelfChangeBuffer.get(this, "getVersions()");
		if (obj != null && obj instanceof Deque) {
			return (Deque) obj;
		}
		
		LinkedList<Version> lst = new LinkedList<Version>();
		if (this.versionStore == null) {
			return lst;
		}
		lst.addAll(
			this.versionStore.getAllVersions()
		);
		
		onSelfChangeBuffer.set(this, "getVersions()", lst);
		return lst;
	}
	
	public Deque<Version> getStableVersions() {
		Object obj = onSelfChangeBuffer.get(this, "getStableVersions()");
		if (obj != null && obj instanceof Deque) {
			return (Deque) obj;
		}

		LinkedList<Version> lst = new LinkedList<Version>();
		if (this.versionStore == null) {
			return lst;
		}
		for (Version version : this.versionStore.getAllVersions()) {
			if (version.getStability()) {
				lst.add(version);
			}
		}
		onSelfChangeBuffer.set(this, "getStableVersions()", lst);
		return lst;
	}
	
	public VersionedObjectStore getVersionedObjectStore() {
		return this.versionStore;
	}
	
	public void saveVersionedObjectStore() throws IOException {
		this.versionStore.save(this.getVersionFile());
	}
	
	public Long getStableVersion() {
		if (this.versionStore == null) {
			return null;
		}
		Version v = this.versionStore.getLatestStable();
		return (v == null) ? null : v.id;
	}
	
	public Long getLatestVersion() {
		if (this.versionStore == null) {
			return null;
		}
		Version v = this.versionStore.getLatestVersion();
		return (v == null) ? null : v.id;
	}
	
	public boolean setVersionStability(long version, boolean stable) {
		Version v = this.versionStore.getVersion(version);
		if (v == null) { return false; }
		v.setStability(stable);
		try {
			this.versionStore.save(this.getVersionFile());
			return true;
		} catch (IOException e) {
			return false;
		}
	}
	
	public static class InheritedVersionInfo {
		public final InheritanceProject project;
		public final Long version;
		public final List<Long> versions;
		public final String description;
		
		public InheritedVersionInfo(
				InheritanceProject project, Long version, List<Long> versions,
				String description) {
			this.project = project;
			this.version = version;
			this.versions = versions;
			this.description = description;
		}
		
		public String toString() {
			return String.format(
					"%s(%d)",
					project.getFullName(),
					version
			);
		}
		
		public List<Long> getVersions() {
			return versions;
		}
	
		public static InheritedVersionInfo getVersionFrom(
				InheritanceProject p, Map<String, Long> predefs) {
			LinkedList<Long> verLst = new LinkedList<Long>();
			for (Version v : p.versionStore.getAllVersions()) {
				verLst.add(v.id);
			}
			Long verId = predefs.get(p.getFullName());
			if (verId == null) {
				verId = p.getStableVersion();
			}
			
			Version verObj = p.versionStore.getVersion(verId);
			return new InheritedVersionInfo(
					p, verId, verLst,
					(verObj != null) ? verObj.getDescription() : null
			);
		}
	}
	
	
	public List<InheritedVersionInfo> getAllInheritedVersionsList() {
		return this.getAllInheritedVersionsList(null);
	}
	
	/**
	 * Returns the versions for all parent projects (including the current one)
	 * based on the information in the given build.
	 * <p>
	 * Do note that this method is recursive and will emit all parents.
	 * It is safe even in the case of cyclical references, even though these
	 * are not permitted/buildable.
	 * 
	 * @param build the build for which to generate the versions.
	 * @return a list of versions.
	 */
	public List<InheritedVersionInfo> getAllInheritedVersionsList(InheritanceBuild build) {
		return InheritanceProject.getAllInheritedVersionsList(this, build);
	}
	
	/**
	 * Static part of {@link #getAllInheritedVersionsList(InheritanceBuild)}.
	 * <p>
	 * This is needed, because an overriding class might want to override
	 * the member method, while still having access to this simple implementation.
	 * <p>
	 * An example would be to add projects that are not part of the inheritance
	 * tree to the list of versions (say, downstream projects). For the
	 * purpose of cycle-avoidance, calling this static method might be necessary.
	 * 
	 * @param root the project for which to fetch versions
	 * @param build the build for which to generate the versions.
	 * @return a list of versions only derived from the inheritance relationship.
	 */
	public static List<InheritedVersionInfo> getAllInheritedVersionsList(
			InheritanceProject root,
			InheritanceBuild build
	) {
		//Create the list of projects and their versions to be returned
		List<InheritedVersionInfo> out = new LinkedList<InheritedVersionInfo>();
		
		//Retrieve the predefined versions from the given build & environment
		Map<String, Long> predefs = new HashMap<String, Long>();
		if (build != null) {
			predefs.putAll(build.getProjectVersions());
		}
		predefs.putAll(VersionHandler.getVersions());
		
		//For each parent, use either the value from the predefs, or their default if missing
		
		//Loop over all jobs in the scope, until all have been seen
		Set<String> seen = new HashSet<String>();
		Deque<AbstractProject<?, ?>> open = new LinkedList<>();
		open.add(root);
		
		while (!open.isEmpty()) {
			AbstractProject<?, ?> ap = open.pop();
			if (seen.contains(ap.getFullName())) {
				continue;
			}
			seen.add(ap.getFullName());
			
			//If the project is an inheritance project; add its own version and
			//look at its parents later
			if (ap instanceof InheritanceProject) {
				InheritanceProject ip = (InheritanceProject) ap;
				InheritedVersionInfo ivf =
						InheritedVersionInfo.getVersionFrom(ip, predefs);
				//Ignore projects with no version
				//Note: Some UI pages will also hide jobs with only 1 version
				if (ivf != null && ivf.version != null) {
					out.add(ivf);
				}
				for (AbstractProjectReference ref : ip.getParentReferences()) {
					AbstractProject<?, ?> par = ref.getProject();
					if (par != null) { open.add(par); }
				}
			}
			
			//Explore references from local wrappers, builders and publishers later
			for (String ref : getReferencers(ap)) {
				AbstractProject it = Jenkins.get().getItemByFullName(
						ref, AbstractProject.class
				);
				if (it == null) { continue; }
				open.push(it);
			}
		}
		
		return out;
	}
	
	/**
	 * This method takes the given job, and adds all jobs pointed to by
	 * {@link Referencer}s in the Wrappers, Builders and Publishers of that job.
	 * <p>
	 * The found jobs are appended to the given {@link Deque}. There is no
	 * attempt made to avoid duplicates.
	 * 
	 * @param ap the project to examine
	 * @return the set of job names to fill with new references in no particular order
	 */
	private static Set<String> getReferencers(AbstractProject<?, ?> ap) {
		if (ap == null) { return Collections.emptySet(); }
		
		List<Referencer> refs = new LinkedList<>();
		
		if (ap instanceof InheritanceProject) {
			InheritanceProject ip = (InheritanceProject) ap;
			for (BuildWrapper w : ip.getRawBuildWrappersList()) {
				if (w instanceof Referencer) { refs.add((Referencer)w); }
			}
			for (Builder w : ip.getRawBuildersList()) {
				if (w instanceof Referencer) { refs.add((Referencer)w); }
			}
			for (Publisher w : ip.getRawPublishersList()) {
				if (w instanceof Referencer) { refs.add((Referencer)w); }
			}
		} else if (ap instanceof AbstractProject<?, ?>) {
			Project<?, ?> p = (Project<?, ?>) ap;
			for (BuildWrapper w : p.getBuildWrappersList()) {
				if (w instanceof Referencer) { refs.add((Referencer)w); }
			}
			for (Builder w : p.getBuildersList()) {
				if (w instanceof Referencer) { refs.add((Referencer)w); }
			}
			for (Publisher w : p.getPublishersList()) {
				if (w instanceof Referencer) { refs.add((Referencer)w); }
			}
		}
		if (refs.isEmpty()) { return Collections.emptySet(); }
		
		//Add all referenced jobs from the sources above
		HashSet<String> out = new HashSet<>();
		for (Referencer r : refs) {
			for (String ref : r.getReferencedJobs()) {
				out.add(ref);
			}
		}
		return out;
	}
	
	
	
	// === INHERITANCE-HELPER METHODS ===
	
	public List<InheritanceProject> getChildrenProjects() {
		Object obj = onChangeBuffer.get(this, "getChildrenProjects");
		if (obj != null && obj instanceof LinkedList) {
			return (LinkedList) obj;
		}
		
		LinkedList<InheritanceProject> lst =
				new LinkedList<InheritanceProject>();
		
		Map<String, InheritanceProject> map = getProjectsMap();
		for (InheritanceProject p : map.values()) {
			//Checking if that project inherits from us
			for (AbstractProjectReference ref : p.getParentReferences()) {
				if (this.name.equals(ref.getName())) {
					lst.add(p);
				}
			}
		}
		
		onChangeBuffer.set(this, "getChildrenProjects", lst);
		return lst;
	}
	
	/**
	 * Returns the immediate parents of this job in the order they have on
	 * the config page.
	 * <p>
	 * If you need the full scope of parents, use
	 * {@link InheritanceGovernor#getFullScopeOrdered(InheritanceProject, SELECTOR, Set)}
	 * 
	 * @return a list of projects. May be empty, but never null.
	 */
	public List<InheritanceProject> getParentProjects() {
		LinkedList<InheritanceProject> lst =
				new LinkedList<InheritanceProject>();
		
		for (AbstractProjectReference ref : this.getParentReferences()) {
			if (ref == null) { continue; }
			InheritanceProject ip = ref.getProject();
			if (ip == null) { continue; }
			lst.add(ip);
		}
		
		return lst;
	}
	
	
	/**
	 * This method re-parents a given trigger, to ensure that it belongs to the
	 * current project.
	 * <p>
	 * It does so by looping it through XStream to get a copy and then calling
	 * {@link Trigger#start(Item, boolean)} on it; just like if the project was
	 * just read from disk.
	 * <p>
	 * As such, this method should be highly robust, but is of course very much
	 * slower than if it had a reliable direct copying method available.
	 * 
	 * @param trigger
	 * @return a copy of the trigger owned by this instance.
	 */
	private <T extends Trigger> T getReparentedTrigger(T trigger) {
		//Copy the trigger by looping it through XSTREAM and then calling start()
		//based on the CURRENT project
		//TODO: Find out somehow if "trigger" already belongs to the current project
		try {
			String xml = Jenkins.XSTREAM2.toXML(trigger);
			if (xml == null) { return trigger; }
			Object copy = Jenkins.XSTREAM2.fromXML(xml);
			if (copy == null || !(copy instanceof Trigger)) {
				return trigger;
			}
			//The copying loop was successful! Calling start() on the trigger
			trigger = (T) copy;
			trigger.start(this, false);
			return trigger;
		} catch (XStreamException ex) {
			//The loop-copy failed; returning the originally retrieved field
			return trigger;
		}
	}
	
	
	// === NON-INHERITANCE CONTROLLED PROPERTY SETTING METHODS ===
	
	/**
	 * This method is called by the configuration submission to set a new
	 * SCM. This does not need to care about inheritance or versioning, as
	 * this function should only be invoked from
	 * {@link #doConfigSubmit(StaplerRequest, StaplerResponse)}.
	 */
	@Override
	public void setScm(SCM scm) throws IOException {
		super.setScm(scm);
	}
	
	
	
	// === INHERITANCE-AWARE PROPERTY READING METHODS ===
	
	private InheritanceGovernor<List<AbstractProjectReference>> getParentReferencesGovernor(ProjectReference.PrioComparator.SELECTOR sortKey) {
		return new InheritanceGovernor<List<AbstractProjectReference>>(
				"parentReferences", sortKey, this) {
			
			@Override
			protected List<AbstractProjectReference> castToDestinationType(
					Object o) {
				return castToList(o);
			}
			
			@Override
			public List<AbstractProjectReference> getRawField(
					InheritanceProject ip) {
				return ip.getRawParentReferences();
			}
			
			@Override
			protected List<AbstractProjectReference> reduceFromFullInheritance(Deque<List<AbstractProjectReference>> list) {
				return InheritanceGovernor.reduceByMergeWithDuplicates(
						list, AbstractProjectReference.class, this.caller
				);
			}
		};
	}
		
	public List<AbstractProjectReference> getParentReferences() {
		return this.getParentReferences(SELECTOR.MISC);
	}
	
	static <T> List<T> nonNull(List<T> v) {
		if (v == null) {
			return Collections.emptyList();
		}
		return v;
	}

	public List<AbstractProjectReference> getParentReferences(
			ProjectReference.PrioComparator.SELECTOR sortKey) {
		InheritanceGovernor<List<AbstractProjectReference>> gov =
				getParentReferencesGovernor(sortKey);
		//We will ALWAYS just return the LOCAL parent references.
		//If you ever do anything else; this WILL cause an infinite loop!
		return nonNull(gov.retrieveFullyDerivedField(this, IMode.LOCAL_ONLY));
	}
	
	public List<AbstractProjectReference> getRawParentReferences() {
		return this.parentReferences;
	}
	
	/**
	 * This method returns a list of all parent references.
	 * <p>
	 * <b><i>DO NOT</i></b> use this method inside any function from
	 * {@link InheritanceGovernor} or any method called by it, because that
	 * will almost always lead to an infinite recursion.
	 * 
	 * @param sortKey the key specifying the order in which projects are returned.
	 * @return a list of all parent references
	 */
	public List<AbstractProjectReference> getAllParentReferences(
			ProjectReference.PrioComparator.SELECTOR sortKey) {
		InheritanceGovernor<List<AbstractProjectReference>> gov =
				this.getParentReferencesGovernor(sortKey);
		return gov.retrieveFullyDerivedField(this, IMode.INHERIT_FORCED);
	}
	
	/**
	 * Wrapper for {@link #getAllParentReferences(ProjectReference.PrioComparator.SELECTOR)},
	 * but will add a reference to this project too, if needed.
	 * 
	 * @param sortKey the key specifying the order in which projects are returned.
	 * @param addSelf if true, add a self-reference in the correct spot
	 * @return a list of all parent references, including a self-reference if
	 * addSelf is true.
	 */
	public List<AbstractProjectReference> getAllParentReferences(
			ProjectReference.PrioComparator.SELECTOR sortKey, boolean addSelf) {
		List<AbstractProjectReference> lst = this.getAllParentReferences(sortKey);
		
		if (addSelf) {
			boolean hasAddedSelf = false;
			ListIterator<AbstractProjectReference> iter = lst.listIterator();
			while (iter.hasNext()) {
				AbstractProjectReference ref = iter.next();
				int prio;
				if (ref instanceof ProjectReference) {
					prio = PrioComparator.getPriorityFor(ref, sortKey);
				} else {
					//An anonymous ref is always at priority 0
					prio = 0;
				}
				if (!hasAddedSelf && prio > 0) {
					hasAddedSelf = true;
					//Put the current element at this place
					iter.set(new SimpleProjectReference(this.getFullName()));
					//And add the replaced element back
					iter.add(ref);
					break;
				}
			}
			//If no position was found, the end is the correct position
			if (!hasAddedSelf) {
				lst.add(new SimpleProjectReference(this.getFullName()));
			}
		}
		
		return lst;
	}
	
	public List<AbstractProjectReference> getCompatibleProjects() {
		return this.getCompatibleProjects(SELECTOR.MISC);
	}
	
	public List<AbstractProjectReference> getCompatibleProjects(
			ProjectReference.PrioComparator.SELECTOR sortKey) {
		InheritanceGovernor<List<AbstractProjectReference>> gov =
				new InheritanceGovernor<List<AbstractProjectReference>>(
						"compatibleProjects", sortKey, this) {
			@Override
			protected List<AbstractProjectReference> castToDestinationType(
					Object o) {
				return castToList(o);
			}
			
			@Override
			public List<AbstractProjectReference> getRawField(
					InheritanceProject ip) {
				return ip.getRawCompatibleProjects();
			}
		};
		//No sense in returning anything but local compatibles
		List<AbstractProjectReference> refs = gov.retrieveFullyDerivedField(this, IMode.LOCAL_ONLY);
		if (refs == null) {
			return new LinkedList<AbstractProjectReference>();
		}
		return refs;
	}
	
	public List<AbstractProjectReference> getRawCompatibleProjects() {
		return this.compatibleProjects;
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<Action> getActions() {
		return this.getActions(IMode.AUTO);
	}
	
	public List<Action> getActions(IMode mode) {
		InheritanceGovernor<List<Action>> gov =
				new InheritanceGovernor<List<Action>>(
						"actions", SELECTOR.MISC, this) {
			@Override
			protected List<Action> castToDestinationType(Object o) {
				return castToList(o);
			}
			
			@Override
			public List<Action> getRawField(
					InheritanceProject ip) {
				return ip.getRawActions();
			}
			
			@Override
			protected List<Action> reduceFromFullInheritance(Deque<List<Action>> list) {
				return InheritanceGovernor.reduceByMerge(
						list, Action.class, this.caller
				);
			}
		};
		//No sense in returning anything but local compatibles
		List<Action> nonTransients = gov.retrieveFullyDerivedField(
				this, IMode.LOCAL_ONLY
		);
		
		//TODO: Buffer the creation of transient actions somehow
		
		/* The above call will only return the non-transient actions. The actual
		 * transient actions have to the spliced in now
		 * 
		 * This can lead to a stack overflow (see the annotation in the comments
		 * for createTransientActions()), so we use the thread-store to register
		 * that the current thread is trying to create transient actions.
		 */
		List<Action> transients;
		ThreadAssocStore tas = ThreadAssocStore.getInstance();
		String key = String.format(
				"project-%s-creates-transients", this.getFullName()
		);
		Object o = tas.getValue(key);
		if (o == null) {
			try {
				//We're not fetching transients; so we fetch them
				tas.setValue(key, this);
				transients = this.createVersionAwareTransientActions();
			} finally {
				tas.clear(key);
			}
		} else {
			//We are already fetching transients and have entered a recursion
			transients = Collections.emptyList();
		}
		
		List<Action> merge = new LinkedList<Action>();
		merge.addAll(nonTransients);
		merge.addAll(transients);
		
		// return the read only list to cause a failure on plugins who try to add an action here
		return Collections.unmodifiableList(merge);
	}

	public List<Action> getRawActions() {
		/* Do notice that the function below will not actually return all
		 * actions; as the override of createTransientActions() causes the
		 * super's transientActions field to be always empty to ensure that
		 * they are not accidentally saved in the version store.
		 */
		return super.getActions();
	}
	
	/**
	 * Overrides the super-function to always return an empty list. This is
	 * vitally important so that the super class' transientActions member is
	 * always kept empty.
	 * <p>
	 * Otherwise, you get the nasty problems that temporary actions contaminate
	 * the versioning archive and generally cause troubles during build.
	 * <p>
	 * The downside with generating this on-the-fly is, that some plugins
	 * themselves call {@link #getActions()} (maybe indirectly), which recurses
	 * back into calling {@link #createVersionAwareTransientActions()}.
	 * <p>
	 * This will cause a stack overflow. The only way to fix this is to
	 * return an empty list if a recursion is detected.
	 * <p>
	 * This function is made final, to avoid any superclass to return something
	 * else other than an empty list. Supertypes <b>must</b> override
	 * {@link #createVersionAwareTransientActions()} to contribute transient
	 * actions.
	 * 
	 * @see #getActions()
	 * @see #getActions(IMode)
	 */
	@Override
	protected final List<Action> createTransientActions() {
		return Collections.emptyList();
	}
	
	/**
	 * Creates a list of temporary {@link Action}s as they are contributed
	 * by the various Builders, Publishers, etc. from the correct version and
	 * with the the correct inheritance.
	 * <p>
	 * Note: This overrides the implementation of the super-classes, because
	 * {@link AbstractProject} accesses the Properties of the class directly
	 * without calling {@link #getProperties()}, which circumvents inheritance.
	 * <p>
	 * As such, the code needs to be copied as-is, unless the Jenkins Core gets
	 * changed on that point.
	 * 
	 * @return a temporary list of actions.
	 */
	protected List<Action> createVersionAwareTransientActions() {
		Vector<Action> ta = new Vector<Action>();
		
		// START Implementation from AbstractProject
		for (JobProperty<? super InheritanceProject> p : this.getAllProperties()) {
			ta.addAll(p.getJobActions(this));
		}
		
		for (TransientProjectActionFactory tpaf : TransientProjectActionFactory.all()) {
			try {
				ta.addAll(Util.fixNull(tpaf.createFor(this))); // be defensive against null
			} catch (Throwable e) {
				log.log(Level.SEVERE, "Could not load actions from " + tpaf + " for " + this, e);
			}
		}
		// END Implementation from AbstractProject
		
		// START Implementation from Project
		for (BuildStep step : this.getBuildersList()) {
			try {
				ta.addAll(step.getProjectActions(this));
			} catch (Exception e) {
				log.log(Level.SEVERE, "Error loading build step.", e);
			}
		}
		for (BuildStep step : this.getPublishersList()) {
			try {
				ta.addAll(step.getProjectActions(this));
			} catch (Exception e) {
				log.log(Level.SEVERE, "Error loading publisher.", e);
			}
		}
		for (BuildWrapper step : this.getBuildWrappersList()) {
			try {
				ta.addAll(step.getProjectActions(this));
			} catch (Exception e) {
				log.log(Level.SEVERE, "Error loading build wrapper.", e);
			}
		}
		
		//TODO: Triggers are not versioned! Eventually, this should be corrected
		for (Trigger trigger : this.getTriggers().values()) {
			try {
				ta.addAll(trigger.getProjectActions());
			} catch (Exception e) {
				log.log(Level.SEVERE, "Error loading trigger.", e);
			}
		}
		// END Implementation from Project
		
		return ta;
	}
	
	public DescribableList<Builder, Descriptor<Builder>> getBuildersListForVersion(Long versionId) {
		return (DescribableList<Builder, Descriptor<Builder>>)this.versionStore.getObject(versionId, "buildersList");
	}
	
	@Override
	public DescribableList<Builder, Descriptor<Builder>> getBuildersList() {
		return this.getBuildersList(IMode.AUTO);
	}
	
	public DescribableList<Builder, Descriptor<Builder>> getBuildersList(
			IMode mode) {
		InheritanceGovernor<DescribableList<Builder, Descriptor<Builder>>> gov =
				new InheritanceGovernor<DescribableList<Builder, Descriptor<Builder>>>(
						"buildersList", SELECTOR.BUILDER, this) {
			@Override
			protected DescribableList<Builder, Descriptor<Builder>> castToDestinationType(Object o) {
				return castToDescribableList(o);
			}
			
			@Override
			public DescribableList<Builder, Descriptor<Builder>> getRawField(
					InheritanceProject ip) {
				return ip.getRawBuildersList();
			}
			
			@Override
			protected DescribableList<Builder, Descriptor<Builder>> reduceFromFullInheritance(
					Deque<DescribableList<Builder, Descriptor<Builder>>> list) {
				return InheritanceGovernor.reduceDescribableByMerge(list);
			}
		};
		
		return gov.retrieveFullyDerivedField(this, mode);
	}
	
	public DescribableList<Builder, Descriptor<Builder>> getRawBuildersList() {
		return super.getBuildersList();
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public DescribableList<BuildWrapper, Descriptor<BuildWrapper>> getBuildWrappersList() {
		return this.getBuildWrappersList(IMode.AUTO);
	}
	
	public DescribableList<BuildWrapper, Descriptor<BuildWrapper>> getBuildWrappersList(
			IMode mode) {
		InheritanceGovernor<DescribableList<BuildWrapper, Descriptor<BuildWrapper>>> gov =
				new InheritanceGovernor<DescribableList<BuildWrapper, Descriptor<BuildWrapper>>>(
						"buildWrappersList", SELECTOR.BUILD_WRAPPER, this) {
			@Override
			protected DescribableList<BuildWrapper, Descriptor<BuildWrapper>> castToDestinationType(Object o) {
				return castToDescribableList(o);
			}
			
			@Override
			public DescribableList<BuildWrapper, Descriptor<BuildWrapper>> getRawField(
					InheritanceProject ip) {
				return ip.getRawBuildWrappersList();
			}
			
			@Override
			protected DescribableList<BuildWrapper, Descriptor<BuildWrapper>> reduceFromFullInheritance(
					Deque<DescribableList<BuildWrapper, Descriptor<BuildWrapper>>> list) {
				return InheritanceGovernor.reduceDescribableByMergeWithoutDuplicates(list);
			}
		};
		
		return gov.retrieveFullyDerivedField(this, mode);
	}
	
	public DescribableList<BuildWrapper, Descriptor<BuildWrapper>> getRawBuildWrappersList() {
		return super.getBuildWrappersList();
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public DescribableList<Publisher,Descriptor<Publisher>> getPublishersList() {
		//Note: Between Jenkins 1.509 and 1.625, the "synchronized" requirement
		//was dropped from this method, removing a source of deadlocks.
		return this.getPublishersList(IMode.AUTO);
	}
	
	public DescribableList<Publisher,Descriptor<Publisher>> getPublishersList(
			IMode mode) {
		InheritanceGovernor<DescribableList<Publisher, Descriptor<Publisher>>> gov =
				new InheritanceGovernor<DescribableList<Publisher, Descriptor<Publisher>>>(
						"publishersList", SELECTOR.PUBLISHER, this) {
			@Override
			protected DescribableList<Publisher, Descriptor<Publisher>> castToDestinationType(Object o) {
				return castToDescribableList(o);
			}
			
			@Override
			public DescribableList<Publisher, Descriptor<Publisher>> getRawField(
					InheritanceProject ip) {
				return ip.getRawPublishersList();
			}
			
			@Override
			protected DescribableList<Publisher, Descriptor<Publisher>> reduceFromFullInheritance(
					Deque<DescribableList<Publisher, Descriptor<Publisher>>> list) {
				return InheritanceGovernor.reduceDescribableByMerge(list);
			}
		};
		
		return gov.retrieveFullyDerivedField(this, mode);
	}
	
	public DescribableList<Publisher, Descriptor<Publisher>> getRawPublishersList() {
		return super.getPublishersList();
	}
	
	
	/**
	 * Returns all triggers defined on this project; or if detected to be
	 * necessary, also all parents.
	 * <p>
	 * @return a map of triggers, might be empty, but never null
	 */
	@Override
	public Map<TriggerDescriptor,Trigger<?>> getTriggers() {
		return this.getTriggers(IMode.AUTO);
	}
	
	public Map<TriggerDescriptor,Trigger<?>> getTriggers(IMode mode) {
		InheritanceGovernor<Collection<Trigger<?>>> gov =
				new InheritanceGovernor<Collection<Trigger<?>>>(
						"triggers", SELECTOR.MISC, this) {
			@Override
			protected Collection<Trigger<?>> castToDestinationType(Object o) {
				try {
					return (Collection<Trigger<?>>) o;
				} catch (ClassCastException e) {
					return null;
				}
			}
			
			@Override
			public Collection<Trigger<?>> getRawField(InheritanceProject ip) {
				Map<TriggerDescriptor, Trigger<?>> raw = ip.getRawTriggers();
				return raw.values();
			}
			
			@Override
			protected Collection<Trigger<?>> reduceFromFullInheritance(Deque<Collection<Trigger<?>>> list) {
				Collection<Trigger<?>> out = new LinkedList<Trigger<?>>();
				for (Collection<Trigger<?>> sub : list) {
					out.addAll(sub);
				}
				return out;
			}
		};
		
		Collection<Trigger<?>> triggers = gov.retrieveFullyDerivedField(this, mode);
		Map<TriggerDescriptor,Trigger<?>> out = new HashMap<TriggerDescriptor,Trigger<?>>();
		for (Trigger<?> t : triggers) {
			Trigger copied = this.getReparentedTrigger(t);
			out.put(copied.getDescriptor(), copied);
		}
		return out;
	}
	
	public Map<TriggerDescriptor,Trigger<?>> getRawTriggers() {
		return super.getTriggers();
	}
	
	/**
	 * Gets the specific trigger, or null if the property is not configured for this job.
	 */
	@Override
	public <T extends Trigger> T getTrigger(Class<T> clazz) {
		return this.getTrigger(clazz, IMode.AUTO);
	}
	
	public <T extends Trigger> T getTrigger(Class<T> clazz, IMode mode) {
		final Class<T> fClazz = clazz;
		InheritanceGovernor<T> gov =
				new InheritanceGovernor<T>(
						"triggers", SELECTOR.MISC, this) {
			@Override
			protected T castToDestinationType(Object o) {
				try {
					return (T) o;
				} catch (ClassCastException e) {
					return null;
				}
			}
			
			@Override
			public T getRawField(InheritanceProject ip) {
				return ip.getRawTrigger(fClazz);
			}
			
			/*
			@Override
			protected T reduceFromFullInheritance(Deque<T> list) {
				//Just select the last trigger; it will be of the correct class
				return list.getLast();
			}
			*/
		};
		
		//Return a trigger that is guaranteed to be owned by the current project
		T trigger = gov.retrieveFullyDerivedField(this, mode);
		return getReparentedTrigger(trigger);
	}
	
	public <T extends Trigger> T getRawTrigger(Class<T> clazz) {
		return super.getTrigger(clazz);
	}
	
	
	@Override
	public Map<JobPropertyDescriptor, JobProperty<? super InheritanceProject>> getProperties() {
		return this.getProperties(IMode.AUTO);
	}
	
	public Map<JobPropertyDescriptor, JobProperty<? super InheritanceProject>> getProperties(IMode mode) {
		List<JobProperty<? super InheritanceProject>> lst = this.getAllProperties(mode);
		if (lst == null || lst.isEmpty()) {
			return Collections.emptyMap();
		}
		
		HashMap<JobPropertyDescriptor, JobProperty<? super InheritanceProject>> map =
				new HashMap<JobPropertyDescriptor, JobProperty<? super InheritanceProject>>();
		
		for (JobProperty<? super InheritanceProject> prop : lst) {
			map.put(prop.getDescriptor(), prop);
		}
		
		return map;
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	@Exported(name="property",inline=true)
	public List<JobProperty<? super InheritanceProject>> getAllProperties() {
		return this.getAllProperties(IMode.AUTO);
	}
	
	public List<JobProperty<? super InheritanceProject>> getAllProperties(IMode mode) {
		//Fetching the variance of the current project; it is necessary
		//to access the correct compatibility setting in the correct parent
		final InheritanceProject rootProject = this;
		
		InheritanceGovernor<List<JobProperty<? super InheritanceProject>>> gov =
				new InheritanceGovernor<List<JobProperty<? super InheritanceProject>>>(
						"properties", SELECTOR.PARAMETER, this) {
			@Override
			protected List<JobProperty<? super InheritanceProject>> castToDestinationType(Object o) {
				return castToList(o);
			}
			
			@Override
			public List<JobProperty<? super InheritanceProject>> getRawField(
					InheritanceProject ip) {
				return ip.getRawAllProperties();
			}
			
			@Override
			protected List<JobProperty<? super InheritanceProject>> reduceFromFullInheritance(
					Deque<List<JobProperty<? super InheritanceProject>>> list) {
				//Add the variances for the root project
				ParametersDefinitionProperty variance =
						rootProject.getVarianceParameters();
				if (variance != null) {
					List<JobProperty<? super InheritanceProject>> varLst =
							new LinkedList<JobProperty<? super InheritanceProject>>();
					varLst.add(variance);
					list.addLast(varLst);
				}
				return InheritanceGovernor.reduceByMerge(
						list, JobProperty.class, this.caller
				);
			}
		};
		
		return gov.retrieveFullyDerivedField(this, mode);
	}
	
	/**
	 * This method will fetch all properties defined for the current project
	 * and only those defined on it.
	 * 
	 * @return the local list of properties.
	 */
	public List<JobProperty<? super InheritanceProject>> getRawAllProperties() {
		return super.getAllProperties();
	}
	
	public ParametersDefinitionProperty getVarianceParameters() {
		if (this.isTransient == false) {
			//No variance is or can possibly be defined
			return null;
		}
		
		//Fetch parents of this project; if any
		List<InheritanceProject> parLst = this.getParentProjects();
		if (parLst == null || parLst.size() < 2) {
			return null;
		}
		
		//Now, determine which parent carries our definition
		for (InheritanceProject ip : parLst) {
			if (ip == null) { continue; }
			
			//A project carrying a variance MUST be the prefix of our name
			if (this.name.startsWith(ip.name) == false) {
				continue;
			}
			
			List<AbstractProjectReference> compatLst =
					ip.getCompatibleProjects();
			if (compatLst == null) { continue; }
			
			for (AbstractProjectReference apr : compatLst) {
				if (!(apr instanceof ParameterizedProjectReference)) {
					continue;
				}
				ParameterizedProjectReference ppr =
						(ParameterizedProjectReference) apr;
				String projVar = ppr.getVariance();
				
				//Checking if the variance do not match up
				if (this.variance == null) {
					if (projVar != null) {
						continue;
					}
				} else {
					if (projVar == null ||
							!this.variance.equals(ppr.getVariance())) {
						continue;
					}
				}
				
				//Now, generating the full name and comparing
				String compatName = ProjectCreationEngine.generateNameFor(
						ppr.getVariance(), ip.name, ppr.getName()
				);
				if (!this.name.equals(compatName)) {
					continue;
				}
				
				//The correct variance description was found; adding its parameters
				return new ParametersDefinitionProperty(ppr.getParameters());
			}
		}
		
		//No variance found
		return null;
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public <T extends JobProperty> T getProperty(Class<T> clazz) {
		return this.getProperty(clazz, IMode.AUTO);
	}
	
	public <T extends JobProperty> T getProperty(Class<T> clazz, IMode mode) {
		/* Note: getAllProperties returns a list of properties in order of
		 * inheritance. Therefore, properties might be defined twice. In these
		 * cases, we need to return the last property.
		 */
		List<JobProperty<? super InheritanceProject>> props =
				this.getAllProperties(mode);
		
		//Checking if we can reverse-iterate the list for more efficiency
		if (props instanceof Deque) {
			Iterator<JobProperty<? super InheritanceProject>> rIter =
					((Deque) props).descendingIterator();
			while (rIter.hasNext()) {
				JobProperty p = rIter.next();
				if (clazz.isInstance(p)) {
					return clazz.cast(p);
				}
			}
		} else {
			for (JobProperty p : props) {
				if (clazz.isInstance(p)) {
					return clazz.cast(p);
				}
			}
		}
		return null;
	}
	

	/**
	 * {@inheritDoc}
	 */
	@Override
	public JobProperty getProperty(String className) {
		return this.getProperty(className, IMode.AUTO);
	}
	
	public JobProperty getProperty(String className, IMode mode) {
		for (JobProperty p : this.getAllProperties(mode)) {
			if (p.getClass().getName().equals(className)) {
				return p;
			}
		}
		return null;
	}

	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public Collection<?> getOverrides() {
		return this.getOverrides(IMode.AUTO);
	}
	
	public Collection<?> getOverrides(IMode mode) {
		List<Object> r = new ArrayList<Object>();
		for (JobProperty<? super InheritanceProject> p : this.getAllProperties(mode)) {
			r.addAll(p.getJobOverrides());
		}
		return r;
	}
	
	/**
	 * This needs to be overridden, because {@link AbstractProject} reads the
	 * properties field directly; which circumvents inheritance.
	 */
	@Override
	public List<SubTask> getSubTasks() {
		List<SubTask> r = new ArrayList<SubTask>();
		r.add(this);
		for (SubTaskContributor euc : SubTaskContributor.all()) {
			r.addAll(euc.forProject(this));
		}
		for (JobProperty<?> p : this.getAllProperties()) {
			r.addAll(p.getSubTasks());
		}
		return r;
	}
	
	public List<ParameterDefinition> getParameters() {
		return this.getParameters(IMode.AUTO);
	}
	
	public List<ParameterDefinition> getParameters(IMode mode) {
		ParametersDefinitionProperty pdp =
				this.getProperty(ParametersDefinitionProperty.class, mode);
		if (pdp == null) {
			return new LinkedList<ParameterDefinition>();
		}
		return pdp.getParameterDefinitions();
	}
	
	
	@Override
	public SCM getScm() {
		return getScm(IMode.AUTO);
	}
	
	public SCM getScm(IMode mode) {
		InheritanceGovernor<SCM> gov =
				new InheritanceGovernor<SCM>(
						"scm", SELECTOR.MISC, this) {
			@Override
			protected SCM castToDestinationType(
					Object o) {
				return (o instanceof SCM) ? (SCM) o : null;
			}
			
			@Override
			public SCM getRawField(
					InheritanceProject ip) {
				return ip.getRawScm();
			}
			
			@Override
			protected SCM reduceFromFullInheritance(Deque<SCM> list) {
				if (list == null || list.isEmpty()) {
					return new NullSCM();
				}
				//Return the SCM that was defined last and is not a NullSCM
				Iterator<SCM> iter = list.descendingIterator();
				while (iter.hasNext()) {
					SCM scm = iter.next();
					if (scm != null && !(scm instanceof NullSCM)) {
						return scm;
					}
				}
				//All SCMs are NullSCMs; so it does not matter which one to return
				return list.peekLast();
			}
		};
		
		SCM scm = gov.retrieveFullyDerivedField(this, mode);
		
		//We may not return null directly
		return (scm == null) ? new NullSCM() : scm;
	}
	
	public SCM getRawScm() {
		return super.getScm();
	}
	
	
	@Override
	public int getQuietPeriod() {
		Integer i = this.getQuietPeriodObject();
		return (i != null) ? i : super.getQuietPeriod();
	}
	
	public Integer getQuietPeriodObject() {
		InheritanceGovernor<Integer> gov =
				new InheritanceGovernor<Integer>(
						"quietPeriod", SELECTOR.MISC, this) {
			@Override
			protected Integer castToDestinationType(
					Object o) {
				return (o instanceof Integer) ? (Integer) o : null;
			}
			
			@Override
			public Integer getRawField(
					InheritanceProject ip) {
				return ip.getRawQuietPeriod();
			}
		};
		
		return gov.retrieveFullyDerivedField(this, IMode.AUTO);
	}
	
	public Integer getRawQuietPeriod() {
		if (super.getHasCustomQuietPeriod()) {
			return super.getQuietPeriod();
		} else {
			return null;
		}
	}
	
	@Override
	public boolean getHasCustomQuietPeriod() {
		Integer i = this.getQuietPeriodObject();
		return i != null;
	}
	
	
	@Override
	public int getScmCheckoutRetryCount() {
		Integer i = this.getScmCheckoutRetryCountObject();
		return (i != null) ? i : super.getScmCheckoutRetryCount();
	}
	
	public Integer getScmCheckoutRetryCountObject() {
		InheritanceGovernor<Integer> gov =
				new InheritanceGovernor<Integer>(
						"scmCheckoutRetryCount", SELECTOR.MISC, this) {
			@Override
			protected Integer castToDestinationType(
					Object o) {
				return (o instanceof Integer) ? (Integer) o : null;
			}
			
			@Override
			public Integer getRawField(
					InheritanceProject ip) {
				return ip.getRawScmCheckoutRetryCount();
			}
		};
		
		return gov.retrieveFullyDerivedField(this, IMode.AUTO);
	}
	
	public Integer getRawScmCheckoutRetryCount() {
		if (super.hasCustomScmCheckoutRetryCount()) {
			return super.getScmCheckoutRetryCount();
		} else {
			return null;
		}
	}

	@Override
	public boolean hasCustomScmCheckoutRetryCount(){
		return this.getScmCheckoutRetryCountObject() != null;
	}

	
	@Override
	public SCMCheckoutStrategy getScmCheckoutStrategy() {
		return getScmCheckoutStrategy(IMode.AUTO);
	}
	
	public SCMCheckoutStrategy getScmCheckoutStrategy(IMode mode) {
		InheritanceGovernor<SCMCheckoutStrategy> gov =
				new InheritanceGovernor<SCMCheckoutStrategy>(
						"scmCheckoutStrategy", SELECTOR.MISC, this) {
			@Override
			protected SCMCheckoutStrategy castToDestinationType(
					Object o) {
				return (o instanceof SCMCheckoutStrategy) ? (SCMCheckoutStrategy) o : null;
			}
			
			@Override
			public SCMCheckoutStrategy getRawField(
					InheritanceProject ip) {
				return ip.getRawScmCheckoutStrategy();
			}
		};
		
		return gov.retrieveFullyDerivedField(this, mode);
	}
	
	public SCMCheckoutStrategy getRawScmCheckoutStrategy() {
		return super.getScmCheckoutStrategy();
	}
	
	
	
	@Override
	public boolean blockBuildWhenDownstreamBuilding() {
		return blockBuildWhenDownstreamBuilding(IMode.AUTO);
	}
	
	public boolean blockBuildWhenDownstreamBuilding(IMode mode) {
		InheritanceGovernor<Boolean> gov =
				new InheritanceGovernor<Boolean>(
						"blockBuildWhenDownstreamBuilding", SELECTOR.MISC, this) {
			@Override
			protected Boolean castToDestinationType(
					Object o) {
				return (o instanceof Boolean) ? (Boolean) o : null;
			}
			
			@Override
			public Boolean getRawField(
					InheritanceProject ip) {
				return ip.getRawBlockBuildWhenDownstreamBuilding();
			}
		};
		
		return gov.retrieveFullyDerivedField(this, mode);
	}
	
	public boolean getRawBlockBuildWhenDownstreamBuilding() {
		return super.blockBuildWhenDownstreamBuilding();
	}
	

	@Override
	public boolean blockBuildWhenUpstreamBuilding() {
		return this.blockBuildWhenUpstreamBuilding(IMode.AUTO);
	}
	
	public boolean blockBuildWhenUpstreamBuilding(IMode mode) {
		InheritanceGovernor<Boolean> gov =
				new InheritanceGovernor<Boolean>(
						"blockBuildWhenUpstreamBuilding", SELECTOR.MISC, this) {
			@Override
			protected Boolean castToDestinationType(
					Object o) {
				return (o instanceof Boolean) ? (Boolean) o : null;
			}
			
			@Override
			public Boolean getRawField(
					InheritanceProject ip) {
				return ip.getRawBlockBuildWhenUpstreamBuilding();
			}
		};
		
		return gov.retrieveFullyDerivedField(this, mode);
	}
	
	public boolean getRawBlockBuildWhenUpstreamBuilding() {
		return super.blockBuildWhenUpstreamBuilding();
	}
	
	@Override
	public String getCustomWorkspace() {
		return getCustomWorkspace(IMode.AUTO);
	}
	
	public String getCustomWorkspace(IMode mode) {
		InheritanceGovernor<String> gov =
				new InheritanceGovernor<String>(
						"customWorkspace", SELECTOR.MISC, this) {
			@Override
			protected String castToDestinationType(
					Object o) {
				return (o instanceof String) ? (String) o : null;
			}
			
			@Override
			public String getRawField(
					InheritanceProject ip) {
				return ip.getRawCustomWorkspace();
			}
		};
		
		return gov.retrieveFullyDerivedField(this, mode);
	}
	
	public String getRawCustomWorkspace() {
		return super.getCustomWorkspace();
	}
	
	
	public String getParameterizedWorkspace() {
		return this.getParameterizedWorkspace(IMode.AUTO);
	}
	
	public String getParameterizedWorkspace(IMode mode) {
		InheritanceGovernor<String> gov =
				new InheritanceGovernor<String>(
						"parameterizedWorkspace", SELECTOR.MISC, this) {
			@Override
			protected String castToDestinationType(
					Object o) {
				return (o instanceof String) ? (String) o : null;
			}
			
			@Override
			public String getRawField(
					InheritanceProject ip) {
				return ip.getRawParameterizedWorkspace();
			}
		};
		
		return gov.retrieveFullyDerivedField(this, mode);
	}
	
	public String getRawParameterizedWorkspace() {
		return this.parameterizedWorkspace;
	}
	
	/**
	 * Sets the parameterized workspace variable.
	 * 
	 * @deprecated Should only be used from within UnitTest classes.
	 *
	 * @param workspace the new value for the parameterized workspace
	 */
	@Deprecated
	public void setRawParameterizedWorkspace(String workspace) {
		this.parameterizedWorkspace = workspace;
	}
	
	
	
	/**
	 * {@inheritDoc}
	 * 
	 * @deprecated as of 1.503
	 *	  Use {@link #getBuildDiscarder()}.
	 */
	@Deprecated
	@Override
	public LogRotator getLogRotator() {
		BuildDiscarder d = this.getBuildDiscarder();
		if (d instanceof LogRotator) {
			return (LogRotator) d;
		}
		return null;
	}
	
	/**
	 * Inheritance-aware Handler for {@link #getLogRotator()}
	 * 
	 * @param mode the inheritance mode
	 * @return the {@link BuildDiscarder} as a {@link LogRotator}.
	 *  
	 * @deprecated as of 1.503
	 *	  Use {@link #getBuildDiscarder(IMode)}.
	 */
	@Deprecated
	public LogRotator getLogRotator(IMode mode) {
		BuildDiscarder d = this.getBuildDiscarder(mode);
		if (d instanceof LogRotator) {
			return (LogRotator) d;
		}
		return null;
	}
	
	@Override
	public BuildDiscarder getBuildDiscarder() {
		return this.getBuildDiscarder(IMode.AUTO);
	}
	
	/**
	 * Inheritance-aware wrapper for {@link #getBuildDiscarder()}
	 * 
	 * @param mode the inheritance mode to use
	 * @return a build discarder, or null if none is present
	 */
	public BuildDiscarder getBuildDiscarder(IMode mode) {
		//Only resolve it via the properties (new Jenkins 2.x approach)
		//The old data gets converted in #updateVersionedObjectStore()
		BuildDiscarderProperty bdProp = 
				this.getProperty(BuildDiscarderProperty.class, mode);
		return (bdProp != null) ? bdProp.getStrategy() : null;
	}
	
	
	/**
	 * {@inheritDoc}
	 * <p>
	 * Contrary to all the other properties methods, this will ALWAYS return
	 * the fully inherited version and will cache the result.
	 * <br>
	 * This is done, because the only time when no inheritance is needed, is
	 * when the project is configured, and this will call
	 * {@link #getAssignedLabel(IMode)} instead with the {@link IMode#LOCAL_ONLY}
	 * set.
	 * <p>
	 * The reason for the caching is, that this method is called quite often
	 * by {@link Queue#maintain()}, a function that potentially blocks the
	 * entire server from progressing with builds.
	 * <br>
	 * Thus, this method must take the minimum possible amount of time, which
	 * means that reflection is too expensive.
	 * <p>
	 * This has the downside of this method ignoring versioning completely,
	 * which might affect the result of this call through changing the
	 * inheritance.
	 * This is an accepted break, compared to the potential slowdown of
	 * {@link Queue#maintain()} under high queue load situations.
	 */
	@Override
	public Label getAssignedLabel() {
		//Check if there's a cached value
		Object cached = onChangeBuffer.get(this, "maintenanceAssignedLabel");
		if (cached != null && cached instanceof Label) {
			Label lbl = (Label) cached;
			/* Use the Jenkins cache to get an up-to-date version of that label
			 * Jenkins will automatically flush cached labels when they change
			 * 
			 * See getAssignedLabel(IMode) to see why the "" quoting
			 * is needed.
			 */
			return Jenkins.get().getLabel(
					'"' + lbl.getName() + '"'
			);
		}
		
		//Generate a new label, forcing inheritance
		Label lbl = this.getAssignedLabel(IMode.INHERIT_FORCED);
		if (lbl == null) {
			lbl = super.getAssignedLabel();
		}
		
		//Caching the result
		if (lbl != null) {
			onChangeBuffer.set(this, "maintenanceAssignedLabel", lbl);
		}
		//The returned label is guaranteed to be fresh
		return lbl;
	}
	
	public Label getAssignedLabel(IMode mode) {
		InheritanceGovernor<Label> gov =
				new InheritanceGovernor<Label>(
						"assignedLabel", SELECTOR.MISC, this) {
			@Override
			protected Label castToDestinationType(
					Object o) {
				if (o instanceof Label) {
					return (Label) o;
				}
				return null;
			}
			
			@Override
			public Label getRawField(
					InheritanceProject ip) {
				return ip.getRawAssignedLabel();
			}
			
			@Override
			protected Label reduceFromFullInheritance(Deque<Label> list) {
				//We simply join the labels via the AND operator
				Label out = null;
				if (list == null || list.isEmpty()) { return out; }
				for (Label l : list) {
					if (l == null) { continue; }
					out = (out == null) ? l : out.and(l);
				}
				return out;
			}
		};
		
		//Fetch the applicable label for this job
		Label lbl = gov.retrieveFullyDerivedField(this, mode);
		if (lbl == null) { return null; }
		
		/* The labels stored in versioning are essentially cached; which means
		 * that their "applicable nodes" list is out-of-date.
		 * 
		 * As such, we will use Jenkins' caching mechanism to update the labels,
		 * as it will "know" when to refresh labels and when not.
		 * Unfortunately, Jenkins is braindead and "unquotes" the strings
		 * aggressively, by just stripping out the outermost and innermost
		 * quote sign; EVEN if the quotes do not belong to each other.
		 * 
		 * E.g.:
		 * 		"os:linux"&&"role:foobar"
		 * will be turned into:
		 * 		os:linux"&&"role:foobar
		 * 
		 * We "solve" this by adding a pointless quote around the label's
		 * string representation
		 */
		return Jenkins.get().getLabel(
				'"' + lbl.getName() + '"'
		);
	}
	
	public Label getRawAssignedLabel() {
		if (this.isTransient) {
			//Transient projects do not have a label, they merely inherit
			return null;
		}
		return super.getAssignedLabel();
	}
	
	@Override
	public String getAssignedLabelString() {
		if (InheritanceGovernor.inheritanceLookupRequired(this) == false) {
			return super.getAssignedLabelString();
		}
		Label lbl = this.getAssignedLabel();
		if (lbl == null) {
			return super.getAssignedLabelString();
		}
		return lbl.getExpression();
	}
	
	
	
	/**
	 * {@inheritDoc}
	 */
	@Exported @Override
	public boolean isConcurrentBuild() {
		//Check if we're called from a configure page; if so, do not inherit
		//In all other cases, do full inheritance
		StaplerRequest req = Stapler.getCurrentRequest();
		if (req != null && req.getRequestURI().endsWith("/configure")) {
			return this.isConcurrentBuildFast(false);
		}
		return this.isConcurrentBuildFast(true);
	}
	
	/**
	 * This method behaves similar to {@link #isConcurrentBuild(IMode)}, but
	 * will not even bother with versioning and skip reflection at all, if no
	 * inheritance is needed.
	 * 
	 * @param inherit whether or not to care about inheritance (versioning is
	 * always ignored)
	 * @return true, if the job is set to run concurrently.
	 */
	public boolean isConcurrentBuildFast(boolean inherit) {
		if (!inherit) {
			return super.isConcurrentBuild();
		}
		boolean isConc = super.isConcurrentBuild();
		if (isConc) { return true; }
		//Otherwise, check the parents' current config
		for (AbstractProjectReference apr: this.getParentReferences()) {
			if (apr == null || apr.getProject() == null) {
				continue;
			}
			if (apr.getProject().isConcurrentBuildFast(inherit)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * This method learns the actual value of concurrency, but is too slow
	 * to be executed thousands of times per second, as the Jenkins default
	 * scheduler often does.
	 * <p>
	 * For faster, non-reflected access, use {@link #isConcurrentBuildFast(boolean)},
	 * if you can live without versioning.
	 * 
	 * @param mode the inheritance mode
	 * @return true, if the job is set to run concurrently.
	 */
	public boolean isConcurrentBuild(IMode mode) {
		InheritanceGovernor<Boolean> gov =
				new InheritanceGovernor<Boolean>(
						"concurrentBuild", SELECTOR.MISC, this) {
			@Override
			protected Boolean castToDestinationType(
					Object o) {
				if (o instanceof Boolean) {
					return (Boolean) o;
				}
				return null;
			}
			
			@Override
			public Boolean getRawField(
					InheritanceProject ip) {
				return ip.isRawConcurrentBuild();
			}
		};
		
		Boolean b = gov.retrieveFullyDerivedField(this, mode);
		return (b != null) ? b : false;
	}
	
	public boolean isRawConcurrentBuild() {
		return super.isConcurrentBuild();
	}
	
	
	/**
	 * In Vanilla-Jenkins, this method is really just a glorious wrapper around
	 * the following call:
	 * <p>
	 * <code>return getProperty(ParametersDefinitionProperty.class) != null;</code>
	 * <p>
	 * That means, its entire inheritance-based implementation would already
	 * be covered by {@link #getProperty(Class)}. But unfortunately, this is
	 * not something we can rely on, as this function &mdash; even though it's
	 * just a wrapper &mdash; fulfills a very specific role:
	 * <p>
	 * When Jenkins creates the sidepanel of a job, it queries this function
	 * to determine, whether the "Build Now" button should trigger a POST (if
	 * not parameterized) or a GET (if parameters need to be queried). Thus,
	 * this function <b>must always</b> return true, if <i>any</i> parent
	 * project is parameterized.
	 * <p>
	 * As such, contrary to the other functions, this function must
	 * <i>always</i> explore full inheritance.
	 */
	@Override
	public boolean isParameterized() {
		ParametersDefinitionProperty pdp =
				this.getProperty(ParametersDefinitionProperty.class, IMode.INHERIT_FORCED);
		return pdp != null;
	}
	
	public boolean isRawParameterized() {
		return super.isParameterized();
	}
	
	
	@Override
	public boolean isBuildable() {
		if (!super.isBuildable()) {
			log.fine(String.format("%s not buildable; super.isBuildable() is false", this.getFullName()));
			return false;
		}
		//Then, we check if it's an abstract job
		if (this.isAbstract) {
			log.fine(String.format("%s not buildable; project is abstract", this.getFullName()));
			return false;
		}
		
		//Check for missing dependencies (recursively in all referenced projects)
		if (!(this.getMissingDependencies().isEmpty())) {
			return false;
		}
		
		//Check for inheritance cycle
		if (this.hasCyclicDependency()) {
			return false;
		}
		
		//Then, we check if there's a parameter inheritance issue with the
		//user selected version
		AbstractMap.SimpleEntry<Boolean, String> paramCheck =
				this.getParameterSanity();
		if (paramCheck.getKey() == false) {
			log.fine(String.format(
					"%s not buildable; Parameter inconsistency: %s",
					this.getFullName(),
					paramCheck.getValue()
			));
			return false;
		}
		
		// Otherwise, we allow things to proceed
		return true;
	}
	
	
	
	// === GUI ACCESS METHODS ===

	public boolean getIsTransient() {
		return this.isTransient;
	}
	
	public String getCreationClass() {
		if (!needsCreationClass()) { return null; }
		return this.creationClass;
	}
	
	/**
	 * This method decides, whether this class needs the
	 * 'project type' / 'creation class' box.
	 * <p>
	 * If it returns true, the Groovy UI code will render a box with a drop-down
	 * allowing the user to change the value of {@link #getCreationClass()}.
	 * <p>
	 * If false, the project type / creation class will always be set to and
	 * return null.
	 * <p>
	 * Marked as protected, since only subclasses should need to access or
	 * alter this value. External code should not need to know this at all.
	 * <p>
	 * Defaults to 'true', unless overridden.
	 * 
	 * @return true, if the project type is needed
	 */
	protected boolean needsCreationClass() {
		return true;
	}
	
	
	
	// === RELATIONSHIP ACCESS METHODS ===
	
	private static class ProjectGraphNode {
		public HashSet<String> parents = new HashSet<String>();
		public HashSet<String> mates = new HashSet<String>();
		public HashSet<String> children = new HashSet<String>();
	}
	
	public static Map<String, ProjectGraphNode> getConnectionGraph() {
		Object obj = onChangeBuffer.get(null, "getConnectionGraph");
		if (obj != null && obj instanceof Map) {
			return (Map) obj;
		}
		
		Map<String, ProjectGraphNode> map =
				new HashMap<String, ProjectGraphNode>();
		
		for (InheritanceProject ip : getProjectsMap().values()) {
			String currName = ip.getFullName();
			ProjectGraphNode currNode = (map.containsKey(currName))
					? map.get(currName)
					: new ProjectGraphNode();
			
			for (AbstractProjectReference apr : ip.getParentReferences()) {
				String parName = apr.getName();
				currNode.parents.add(parName);
				ProjectGraphNode parNode = (map.containsKey(parName))
						? map.get(parName)
						: new ProjectGraphNode();
				parNode.children.add(currName);
				map.put(parName, parNode);
			}
			for (AbstractProjectReference apr : ip.getCompatibleProjects()) {
				currNode.mates.add(apr.getName());
			}
			map.put(currName, currNode);
		}
		
		onChangeBuffer.set(null, "getConnectionGraph", map);
		return map;
	}

	public Collection<InheritanceProject> getRelationshipsOfType(Relationship.Type type) {
		Collection<InheritanceProject> relationshipsOfType = new LinkedList<InheritanceProject>();
		Map<InheritanceProject, Relationship> relationships = getRelationships();
		
		/*
		 * we are interested in getting the children ordered by last build time
		 * if last build time exists
		 */
		if (type == Relationship.Type.CHILD) {
			relationshipsOfType = getChildrenByBuildDate(relationships);
		} else if (type == Relationship.Type.PARENT) {
			LinkedList<InheritanceProject> parents = new LinkedList<InheritanceProject>();
			for (java.util.Map.Entry<InheritanceProject, Relationship> project : relationships.entrySet()) {
				if (Relationship.Type.PARENT == project.getValue().type) {
					parents.add(project.getKey());
				}
			}
			relationshipsOfType = parents;
		}
		
		return relationshipsOfType;
	}
	
	private class RunTimeComparator implements Comparator<InheritanceProject> {
		public int compare(InheritanceProject a, InheritanceProject b) {
			InheritanceBuild aBuild = a.getLastBuild();
			InheritanceBuild bBuild = b.getLastBuild();
			if (aBuild == null) {
				int retVal = (bBuild == null) ? a.getFullName().compareTo(b.getFullName()) : 1;
				return retVal;
			} else if (bBuild == null) {
				int retVal = (aBuild == null) ? a.getFullName().compareTo(b.getFullName()) : -1;
				return retVal;
			}
			return bBuild.getTime().compareTo(aBuild.getTime());
		}
	}
	
	/**
	 * Returns the children in this relationship map ordered by last build
	 * start time, if a last build exists
	 * 
	 * @param relationships the map containing the inheritance relationships
	 * @return a collections of children, ordered by their last build start date.
	 */
	public Collection<InheritanceProject> getChildrenByBuildDate(Map<InheritanceProject, Relationship> relationships) {
		//Using a TreeSet to do the sorting for last-build-time for us
		TreeSet<InheritanceProject> tree = new TreeSet<InheritanceProject>(
				new RunTimeComparator()
		);
		Map<InheritanceProject, Relationship> relations = this.getRelationships();
		if (relations.isEmpty()) { return tree; }
		//Filtering for buildable children
		for (Map.Entry<InheritanceProject, Relationship> pair : relations.entrySet()) {
			InheritanceProject child = pair.getKey();
			Relationship.Type type = pair.getValue().type;
			//Excluding non-childs
			if (type != Relationship.Type.CHILD) { continue; }
			//The child is buildable, so add it to the tree
			tree.add(child);
		}
		return tree;
	}
	
	public Map<InheritanceProject, Relationship> getRelationships() {
		Object obj = onInheritChangeBuffer.get(this, "getRelationships");
		if (obj != null && obj instanceof Map) {
			return (Map) obj;
		}
		
		//Creating the returned map and pre-filling it with empty lists
		Map<InheritanceProject, Relationship> map =
				new HashMap<InheritanceProject, Relationship>();
		
		//Preparing the set of projects that were already explored
		HashSet<String> seenProjects = new HashSet<String>();
		
		//Fetching the map of all projects and their connections
		Map<String, ProjectGraphNode> connGraph = getConnectionGraph();
		
		//Fetching the node for the current (this) project
		ProjectGraphNode node = connGraph.get(this.getFullName());
		if (node == null) { return map; }
		
		//Mates can be filled quite easily
		for (String mate : node.mates) {
			InheritanceProject p = InheritanceProject.getProjectByName(mate);
			ProjectGraphNode mateNode = connGraph.get(mate);
			boolean isLeaf = (mateNode == null) ? true : mateNode.children.isEmpty();
			if (p == null) { continue; }
			//Checking if we've seen this mate already
			if (!seenProjects.contains(p.getFullName())) {
				map.put(p, new Relationship(Relationship.Type.MATE, 0, isLeaf));
				seenProjects.add(p.getFullName());
			}
		}
		
		//Exploring parents
		int distance = 1;
		seenProjects.clear();
		LinkedList<InheritanceProject> cOpen =
				new LinkedList<InheritanceProject>();
		LinkedList<InheritanceProject> nOpen =
				new LinkedList<InheritanceProject>();
		cOpen.add(this);
		while (!cOpen.isEmpty()) {
			InheritanceProject ip = cOpen.pop();
			if (ip == null || seenProjects.contains(ip.getFullName())) {
				continue;
			}
			seenProjects.add(ip.getFullName());
			
			node = connGraph.get(ip.getFullName());
			if (ip == null || node == null) { continue; }
			//Adding all parents
			for (String parent : node.parents) {
				InheritanceProject par = InheritanceProject.getProjectByName(parent);
				if (par == null || seenProjects.contains(parent)) {
					continue;
				}
				map.put(par, new Relationship(Relationship.Type.PARENT, distance, false));
				nOpen.push(par);
			}
			if (cOpen.isEmpty() && !nOpen.isEmpty()) {
				cOpen = nOpen;
				nOpen = new LinkedList<InheritanceProject>();
				distance++;
			}
		}
		
		//Exploring children
		distance = 1; seenProjects.clear();
		cOpen.clear(); nOpen.clear();
		cOpen.add(this);
		while (!cOpen.isEmpty()) {
			InheritanceProject ip = cOpen.pop();
			if (ip == null || seenProjects.contains(ip.getFullName())) {
				continue;
			}
			seenProjects.add(ip.getFullName());
			
			node = connGraph.get(ip.getFullName());
			if (ip == null || node == null) { continue; }
			//Adding all parents
			for (String child : node.children) {
				InheritanceProject cProj = InheritanceProject.getProjectByName(child);
				if (cProj == null || seenProjects.contains(child)) {
					continue;
				}
				ProjectGraphNode childNode = connGraph.get(child);
				boolean isLeaf = (childNode == null) ? true : childNode.children.isEmpty();
				map.put(cProj, new Relationship(Relationship.Type.CHILD, distance, isLeaf));
				nOpen.push(cProj);
			}
			if (cOpen.isEmpty() && !nOpen.isEmpty()) {
				cOpen = nOpen;
				nOpen = new LinkedList<InheritanceProject>();
				distance++;
			}
		}
		
		onInheritChangeBuffer.set(this, "getRelationships", map);
		return map;
	}
	
	public List<Vector<String>> getRelatedProjects() {
		Object obj = onInheritChangeBuffer.get(this, "getRelatedProjects");
		if (obj != null && obj instanceof LinkedList) {
			return (LinkedList) obj;
		}
		
		LinkedList<Vector<String>> lst = new LinkedList<Vector<String>>();
		
		//Fetching the relationships of this project to others
		Map<InheritanceProject, Relationship> rels = this.getRelationships();
		for (Map.Entry<InheritanceProject, Relationship> entry : rels.entrySet()) {
			Relationship rel = entry.getValue();
			Vector<String> vec = new Vector<String>();
			vec.add(entry.getKey().getFullName());
			switch (rel.type) {
				case PARENT:
					vec.add(Messages.InheritanceProject_Relationship_Type_ParentDesc());
					break;
				case CHILD:
					vec.add(Messages.InheritanceProject_Relationship_Type_ChildDesc());
					break;
				case MATE:
					vec.add(Messages.InheritanceProject_Relationship_Type_MateDesc());
					break;
			}
			vec.add(Integer.toString(rel.distance));
			lst.add(vec);
		}
		
		onInheritChangeBuffer.set(this, "getRelatedProjects", lst);
		return lst;
	}
	
	/**
	 * Wrapper around {@link ParameterSelector#getAllScopedParameterDefinitions(InheritanceProject)}.
	 * 
	 * @return a list of scoped entries, never null but may be empty.
	 */
	private List<ScopeEntry> getFullParameterScope() {
		return ParameterSelector.instance.getAllScopedParameterDefinitions(this);
	}
	
	public List<ParameterDerivationDetails> getParameterDerivationList() {
		List<ParameterDerivationDetails> list =
				new LinkedList<ParameterDerivationDetails>();
		//Grab the full scope of all parameters
		List<ScopeEntry> fullScope = this.getFullParameterScope();
		
		int cnt = 0;
		for (ScopeEntry scope : fullScope) {
			String paramName = scope.param.getName();
			String projName = scope.owner;
			String detail = "";
			Object def = scope.param.getDefaultParameterValue().getValue();
			
			
			if (scope.param instanceof InheritableStringParameterDefinition) {
				InheritableStringParameterDefinition ispd =
						(InheritableStringParameterDefinition) scope.param;
				StringBuilder b = new StringBuilder();
				b.append(ispd.getMustHaveDefaultValue());
				b.append("; ");
				b.append(ispd.getMustBeAssigned());
				detail = b.toString();
			}
			
			ParameterDerivationDetails pdd = new ParameterDerivationDetails(
				paramName, projName, detail, def
			);
			pdd.setOrder(cnt++);
			list.add(pdd);
		}
		
		return list;
	}
	

	// === SVGNode METHODS ===
	
	public String getSVGLabel() {
		return this.getFullName();
	}

	public String getSVGDetail() {
		List<ParameterDefinition> pLst = this.getParameters(IMode.LOCAL_ONLY);
		if (pLst == null) {
			return "";
		}
		StringBuilder b = new StringBuilder();
		for (ParameterDefinition pd : pLst) {
			if (pd == null) { continue; }
			b.append(pd.getName());
			ParameterValue pv = pd.getDefaultParameterValue();
			if (pv != null && pv instanceof StringParameterValue) {
				b.append(": ");
				b.append(((StringParameterValue)pv).getValue().toString());
			}
			b.append('\n');
		}
		
		if (b.length() > 0) {
			b.append("\r\n");
		}
		
		List<Builder> builders = this.getBuilders();
		String str = (builders == null || builders.size() != 1) ? "steps" : "step";
		int num = (builders == null) ? 0 : builders.size();
		b.append(String.format(
				"%d build %s\n", num, str
		));
		
		DescribableList<Publisher, Descriptor<Publisher>> pubs = this.getPublishersList();
		str = (pubs == null || pubs.size() != 1) ? "publishers" : "publisher";
		num = (pubs == null) ? 0 : pubs.size();
		b.append(String.format(
				"%d %s", num, str
		));
		
		
		
		return b.toString();
	}

	public URL getSVGLabelLink() {
		try {
			return new URL(this.getAbsoluteUrl());
		} catch (MalformedURLException ex) {
			return null;
		}
	}
	
	public Graph<SVGNode> getSVGRelationGraph() {
		Graph<SVGNode> out = new Graph<SVGNode>();
		
		LinkedList<InheritanceProject> open = new LinkedList<InheritanceProject>();
		HashSet<InheritanceProject> visited = new HashSet<InheritanceProject>();
		
		open.add(this);
		while (!open.isEmpty()) {
			InheritanceProject ip = open.pop();
			if (visited.contains(ip)) {
				continue;
			} else {
				visited.add(ip);
			}
			out.addNode(ip);
			
			for (InheritanceProject parent : ip.getParentProjects()) {
				open.add(parent);
				out.addNode(ip, parent);
			}
		}
		
		return out;
	}
	
	public String doRenderSVGRelationGraph() {
		return this.renderSVGRelationGraph(0, 0);
	}
	
	public String renderSVGRelationGraph(int width, int height) {
		SVGTreeRenderer tree = new SVGTreeRenderer(
				this.getSVGRelationGraph(), width, height
		);
		Document doc = tree.render();
		try {
			DOMSource source = new DOMSource(doc);
			StringWriter stringWriter = new StringWriter();
			StreamResult result = new StreamResult(stringWriter);
			TransformerFactory factory = TransformerFactory.newInstance();
			Transformer transformer = factory.newTransformer();
			transformer.transform(source, result);
			return stringWriter.getBuffer().toString();
		} catch (TransformerConfigurationException e) {
			e.printStackTrace();
		} catch (TransformerException e) {
			e.printStackTrace();
		}
		return "";
	}
	
	
	
	// === MISC. HELPER METHODS AND CLASSES ===
	
	public static class Dependency implements Comparable<Dependency> {
		public final String ref;
		public final List<String> trace;
		
		public Dependency(String ref, String... trace) {
			this.ref = ref;
			this.trace = (trace == null)
					? Collections.<String>emptyList()
					: Arrays.asList(trace);
		}
		
		public Dependency(String ref, List<String> trace) {
			this.ref = ref;
			this.trace = new LinkedList<>(trace);
		}

		@Override
		public int compareTo(Dependency other) {
			String s = Joiner.on(":").join(this.trace);
			s = (s.isEmpty()) ? this.ref : s + ":" + this.ref;
			
			String o = Joiner.on(":").join(other.trace);
			o = (o.isEmpty()) ? other.ref : o + ":" + other.ref;
			
			return s.compareTo(o);
		}
	}
	
	/**
	 * Checks if there are missing dependencies somewhere in the inheritance
	 * or automated compound generation.
	 * <p>
	 * Returns the information about all the missing references as a
	 * {@link Dependency} instance. This includes the full trackback from the
	 * current project.
	 * <p>
	 * @return a list of missing {@link Dependency} instances. May be empty, but never null.
	 * @see ProjectCreationEngine
	 */
	public final Collection<Dependency> getMissingDependencies() {
		//Preparing the set of project names that were seen at least once
		HashSet<AbstractProject<?, ?>> seen = new HashSet<>();
		
		//The output will be sorted according to the Dependency natural order
		TreeSet<Dependency> missing = new TreeSet<>();
		
		//Creating the list of parent projects to still explore (and the trace of how we got there
		LinkedList<Map.Entry<AbstractProject<?, ?>, List<String>>> open = new LinkedList<>();
		
		//And scheduling ourselves as the first to evaluate
		Map.Entry<AbstractProject<?, ?>, List<String>> entry =
				new AbstractMap.SimpleEntry<AbstractProject<?, ?>, List<String>>(
						this, Collections.<String>emptyList()
		);
		open.push(entry);
		
		while (!(open.isEmpty())) {
			entry = open.pop();
			AbstractProject<?, ?> ap = entry.getKey();
			List<String> trace = entry.getValue();
			
			//Check if we've looked at that project already
			if (ap == null || seen.contains(ap)) { continue; }
			seen.add(ap);
			
			//Expanding the trace, by the currently examined project
			trace = new LinkedList<>(trace);
			if (ap != this) { trace.add(ap.getFullName()); }
			
			//Convert to IP, if possible
			InheritanceProject ip = (ap instanceof InheritanceProject)
					? (InheritanceProject) ap : null;
			
			//Examine parent references
			if (ip != null) {
				for (AbstractProjectReference ref : ip.getParentReferences()) {
					AbstractProject<?, ?> next = ref.getProject();
					if (next == null) {
						//Found a missing dep
						missing.add(new Dependency(ref.getName(), trace));
					} else {
						//Scheduling that job for later examination
						open.push(new AbstractMap.SimpleEntry<AbstractProject<?, ?>, List<String>>(
								next, trace
						));
					}
				}
			}
			
			//Add jobs pointed-to by referencer instances from that job
			for (String ref : getReferencers(ap)) {
				AbstractProject<?, ?> next = Jenkins.get().getItemByFullName(
						ref, AbstractProject.class
				);
				if (next == null) {
					//Found a missing dep
					missing.add(new Dependency(ref, trace));
				} else {
					//Explore that job later
					open.push(new AbstractMap.SimpleEntry<AbstractProject<?, ?>, List<String>>(
							next, trace
					));
				}
			}
		}
		
		//Examine project matings -- only for local item
		for (AbstractProjectReference ref : this.compatibleProjects) {
			InheritanceProject next = ref.getProject();
			if (next == null) {
				//Found a missing dep
				missing.add(new Dependency(
						ref.getName(),
						Collections.<String>emptyList()
				));
			}
			//Mating references are not followed for missing parents, as
			//this affects a child, instead of the local Project
		}
		
		return missing;
	}
	
	/**
	 * Wrapper for {@link #hasCyclicDependency(String...)} with no new project
	 * references added on top of the existing ones.
	 * <p>
	 * Note, that the result is cached and only refreshed when this job or its
	 * parents change. This is done, because cycle checks are expensive and
	 * this method is called often.
	 * <p>
	 * This means, that the result of this method is <b><i>not</i></b> version
	 * aware. If you need an uncached and version-aware result, call
	 * {@link #hasCyclicDependency(boolean, String...)} directly.
	 * 
	 * @return true, if a cycle or diamond was detected.
	 */
	public final boolean hasCyclicDependency() {
		//TODO: Make this method version-aware
		
		//Checking if a result is buffered
		Object obj = onInheritChangeBuffer.get(this, "hasCyclicDependency");
		if (obj != null && obj instanceof Boolean) {
			return (Boolean) obj;
		}
		
		//Re-compute the result
		Boolean bufRes = this.hasCyclicDependency(true);
		
		onInheritChangeBuffer.set(this, "hasCyclicDependency", bufRes);
		return bufRes;
	}
	
	/**
	 * Wrapper around {@link #hasCyclicDependency(boolean, String...)}, with
	 * addExisting set to true.
	 * 
	 * @param whenTheseProjectsAdded the project to check for cycles for
	 * @return true, if there is a cyclic, diamond or repeated dependency among
	 * this project's parents.
	 */
	public final boolean hasCyclicDependency(String[] whenTheseProjectsAdded) {
		return this.hasCyclicDependency(true, whenTheseProjectsAdded);
	}
	
	/**
	 * Tests if this project's configuration leads to a cyclic, diamond or
	 * multiple dependency.<br>
	 * <br>
	 * See <a href="http://en.wikipedia.org/wiki/Cycle_detection">cycle detection</a> and
	 * <a href="http://en.wikipedia.org/wiki/Diamond_problem">diamond problem</a>.
	 * 
	 * @param addExisting whether or not to consider existing jobs for cyclicality.
	 * @param whenTheseProjectsAdded projects about to be added to the dependency graph
	 * 
	 * @return true, if there is a cyclic, diamond or repeated dependency among
	 * this project's parents.
	 */
	public final boolean hasCyclicDependency(boolean addExisting, String... whenTheseProjectsAdded) {
		/* TODO: While this method runs reasonably fast, it is run very often
		 * As such, find a way to buffer the result across all projects and
		 * only rebuild if necessary.
		 */
		
		/* TODO: Further more, this method is not space-optimal
		 * See: http://en.wikipedia.org/wiki/Cycle_detection
		 * But do note that any replacement algorithm also, by contract, needs
		 * to detect multiple inheritance and its special case of diamond
		 * inheritance.
		 */
		
		//Preparing a Deque, that tracks the parents to be processed
		Deque<InheritanceProject> open = new LinkedList<>();
		if (whenTheseProjectsAdded != null) {
			for (String ref : whenTheseProjectsAdded) {
				InheritanceProject p = Jenkins.get().getItemByFullName(
						ref, InheritanceProject.class
				);
				if (p != null) { open.add(p); }
			}
		}
		if (addExisting) {
			for (AbstractProjectReference par : this.getParentReferences()) {
				InheritanceProject p = par.getProject();
				if (p != null) { open.add(p); }
			}
		}
		
		//Preparing the set of project names that were seen at least once
		HashSet<String> closed = new HashSet<String>();
		//We've always seen "ourselves"
		closed.add(this.getFullName());
		
		
		//Loop over all open parents, until all have been processed.
		while (!open.isEmpty()) {
			//Popping the first element
			InheritanceProject p = open.pop();
			//Checking if we've seen that parent already
			if (closed.contains(p.name)) {
				//Detected a cyclic dependency
				return true;
			}
			// Otherwise, we add all its parents to our open set, to be explored
			for (AbstractProjectReference ref : p.getParentReferences()) {
				InheritanceProject refP = ref.getProject();
				if (refP != null) {
					open.push(refP);
				}
			}
			closed.add(p.name);
		}
		// If we reach this spot, there is no such dependency
		return false;
	}
	
	
	/**
	 * This method checks if the current project has a valid set of parameters
	 * through inheritance.
	 * <p>
	 * Do note that this check is different from and complementary to
	 * {@link InheritanceParametersDefinitionProperty#checkParameterSanity(InheritanceBuild, hudson.model.BuildListener)}.
	 * <p>
	 * Of special note here is that this method does not (and can't) check the
	 * final values of the variables. This mostly affects the 'mustBeAssigned'
	 * flag.
	 * 
	 * @return a tuple of whether the assignment is sane and a human-readable
	 * representation of the error -- if any.
	 */
	public final AbstractMap.SimpleEntry<Boolean, String> getParameterSanity() {
		//Creating a small local class to store sanity information
		final class SanityRestrictions {
			public Class<?> hasToBeOfThisClass;
			
			public boolean mustHaveDefault;
			public boolean mustBeAssigned;
			
			public boolean hadDefaultSet;
			public IModes previousMode;
		}
		
		//Preparing a map of parameter name to restrictions
		HashMap<String, SanityRestrictions> resMap =
				new HashMap<String, SanityRestrictions>();
		
		//Fetch all parameters in the scope
		List<ScopeEntry> fullScope = this.getFullParameterScope();
		
		//Iterating through the parameters, and verifying their restrictions on-the-fly
		for (ScopeEntry scope : fullScope) {
			ParameterDefinition pd = scope.param;
			if (pd == null) { continue; }
			SanityRestrictions s = resMap.get(pd.getName());
			if (s == null) {
				//We've seen this PD for the first time
				s = new SanityRestrictions();
				s.hasToBeOfThisClass = pd.getClass();
				if (pd instanceof InheritableStringParameterDefinition) {
					InheritableStringParameterDefinition ispd =
							(InheritableStringParameterDefinition) pd;
					s.mustHaveDefault = ispd.getMustHaveDefaultValue();
					s.mustBeAssigned = ispd.getMustBeAssigned();
					
					String defVal = ispd.getDefaultValue();
					s.hadDefaultSet = !(defVal == null || defVal.isEmpty());
					
					s.previousMode = ispd.getInheritanceModeAsVar();
				} else {
					s.mustHaveDefault = false;
					s.previousMode = IModes.OVERWRITABLE;
				}
				
				//No sense in checking this param instance further, as a
				//param can't make itself insane
				resMap.put(pd.getName(), s);
				continue;
			}
			
			/* Check if the scoped forms can be cast in at least one direction,
			 * which means that they share parenthood.
			 * This avoids an IntegerParamer becoming a StringParameter, but
			 * allows a PasswordParameter to merge with a StringParameter.
			 */
			boolean isScopeCastToCurrent = pd.getClass().isAssignableFrom(s.hasToBeOfThisClass);
			boolean isCurrentCastToScope = s.hasToBeOfThisClass.isAssignableFrom(pd.getClass());
			if (!(isScopeCastToCurrent || isCurrentCastToScope)) {
				return new AbstractMap.SimpleEntry<Boolean, String>(
						false, "Parameter '" + pd.getName() +
						"' redefined with different class name."
				);
			}
			
			if (s.previousMode == IModes.FIXED) {
				return new AbstractMap.SimpleEntry<Boolean, String>(
						false, "Fixed parameter '" + pd.getName() +
						"' may not be redefined at all."
				);
			}
			
			//Check additional restrictions on ISPDs
			if (pd instanceof InheritableStringParameterDefinition) {
				InheritableStringParameterDefinition ispd =
						(InheritableStringParameterDefinition) pd;
				//Check if overwriting causes a previous default to be lost
				String defVal = ispd.getDefaultValue();
				boolean defValNewlySet = !(defVal == null || defVal.isEmpty());
				
				switch(s.previousMode) {
					case OVERWRITABLE:
						//An overwrite always causes the default to be discarded
						s.hadDefaultSet = defValNewlySet;
						break;
					case EXTENSIBLE:
						//An extension does not overwrite an already set default
						if (!s.hadDefaultSet) {
							s.hadDefaultSet = defValNewlySet;
						}
						break;
					case FIXED:
						//FIXED parameters are ignored
						break;
					default:
						log.warning(
								"Detected invalid inheritance mode: " +
								s.previousMode.toString() + " on " +
								this.getFullName() + "->" + pd.getName()
						);
						break;
				}
				
				//Ignore references, as they can never invalidate or change flags
				if (pd instanceof InheritableStringParameterReferenceDefinition) {
					continue;
				}
				
				//Check if the "force-default-value" flag was unset
				if (s.mustHaveDefault && !ispd.getMustHaveDefaultValue()) {
					return new AbstractMap.SimpleEntry<Boolean, String>(
							false, "Parameter '" + pd.getName() +
							"' may not unset the flag that ensures that a" +
							" default value is set."
							);
				}
				//Check if the "must-be-assigned" flag was unset
				if (s.mustBeAssigned && !ispd.getMustBeAssigned()) {
					return new AbstractMap.SimpleEntry<Boolean, String>(
							false, "Parameter '" + pd.getName() +
							"' may not unset the flag that ensures that a" +
							" final value is set before execution."
							);
				}
				//Overwrite the flags, now that their sanity is ensured
				s.previousMode = ispd.getInheritanceModeAsVar();
				s.mustHaveDefault = ispd.getMustHaveDefaultValue();
				s.mustBeAssigned = ispd.getMustBeAssigned();
			}
		}
		
		//Then, if the build is not abstract, we must check whether all values
		//that carry defaults actually had defaults defined at some point
		if (this.isAbstract == false) {
			List<String> mandatoryParams = new ArrayList<>();
			for (Map.Entry<String, SanityRestrictions> e : resMap.entrySet()) {
				SanityRestrictions s = e.getValue();
				if (s.mustHaveDefault && !s.hadDefaultSet) {
					mandatoryParams.add(e.getKey());
				}
			}
			
			if (!mandatoryParams.isEmpty()) {
				return new AbstractMap.SimpleEntry<Boolean, String>(false,
						String.format(Messages.InheritanceProject_ErrorMsg_ParameterDefaultValue(),
								StringUtils.join(mandatoryParams," ',' ")));
			}
		}
		
		//If we reach this spot, everything checked out fine.
		return new AbstractMap.SimpleEntry<Boolean, String>(true, "");
	}
	
	@Override
	public String getPronoun() {
		if (this.getIsTransient()) {
			return Messages.InheritanceProject_TransientPronounLabel();
		}
		//Use the Creation Class -- if any
		String cClass = this.getCreationClass();
		if (!StringUtils.isBlank(cClass)) {
			return cClass;
		}
		//Fallback
		return super.getPronoun();
	}


	/**
	 * {@inheritDoc}
	 * 
	 * The above is overridden in a way, that the Build-History widget is
	 * removed if the build is abstract and can't be run anyway.
	 * 
	 * This is ignored, in case there is a last build, though, to not
	 * hide any information.
	 */
	@Override
	public List<Widget> getWidgets() {
		List<Widget> widgets = super.getWidgets();
		List<Widget> strippedOffWidgets = new ArrayList<Widget>();
		BuildHistoryWidget<InheritanceProject> bhw = null;
		//Remove the history widgets
		for (Widget widget : widgets) {
			if (!(widget instanceof HistoryWidget<?, ?>)) {
				strippedOffWidgets.add(widget);
			} else {
				if (widget instanceof BuildHistoryWidget<?>) {
					bhw = (BuildHistoryWidget<InheritanceProject>)widget;
				}
			}
		}
		if (!this.isBuildable() && this.getLastBuild() == null) {
			return strippedOffWidgets;
		} else {
			if (bhw != null) {
				ExtendedBuildHistoryWidget ibhw =
						new ExtendedBuildHistoryWidget(bhw.owner, bhw.baseList, bhw.adapter);
				strippedOffWidgets.add(ibhw);
				return strippedOffWidgets;
			} else {
				//add back what we removed as a first step in the method
				strippedOffWidgets.add(bhw);
				return strippedOffWidgets;
			}
		}
	}
	
	
	public static List<JobPropertyDescriptor> getJobPropertyDescriptors(
			Class<? extends Job> clazz,
			boolean filterIsExcluding, String... filters) {
		List<JobPropertyDescriptor> out = new ArrayList<JobPropertyDescriptor>();
		
		//JobPropertyDescriptor.getPropertyDescriptors(clazz);
		List<JobPropertyDescriptor> allDesc = Functions.getJobPropertyDescriptors(clazz);
		
		for (JobPropertyDescriptor desc : allDesc) {
			String dName = desc.getClass().getName();
			if (filters.length > 0) {
				boolean matched = false;
				if (filters != null) {
					for (String filter : filters) {
						if (dName.contains(filter)) {
							matched = true;
							break;
						}
					}
				}
				if (filterIsExcluding && matched) {
					continue;
				} else if (!filterIsExcluding && !matched) {
					continue;
				}
			}
			//The class has survived the filter
			out.add(desc);
		}
		
		//At last, we make sure to sort the fields by full name; to ensure
		//that properties from the same package/plugin are next to each other
		Collections.sort(out, new Comparator<JobPropertyDescriptor>() {
			@Override
			public int compare(JobPropertyDescriptor o1,
					JobPropertyDescriptor o2) {
				String c1 = o1.getClass().getName();
				String c2 = o2.getClass().getName();
				return c1.compareTo(c2);
			}
		});
		
		return out;
	}
	
	
	
	// === HELPER METHODS FOR READONLY VIEW ===

	public Map<AbstractProjectReference, List<Builder>> getBuildersFor(
			Map<String, Long> verMap, Class<?> clazz
	) {
		Map<String, Long> oldVerMap = VersionHandler.getVersions();
		try {
			//Set the current thread's versioning map
			if (verMap != null && !verMap.isEmpty()) {
				VersionHandler.initVersions(verMap);
			}
			
			//Loop over all parents and create a joined map of all builders
			Map<AbstractProjectReference, List<Builder>> out =
					new LinkedHashMap<AbstractProjectReference, List<Builder>>();
			
			//Get all parents recursively, with "this" project spliced in at the
			//correct position.
			List<AbstractProjectReference> refs =
					new LinkedList<AbstractProjectReference>(
							this.getAllParentReferences(SELECTOR.BUILDER, true)
					);
			
			for (AbstractProjectReference apr : refs) {
				InheritanceProject ip = apr.getProject();
				if (ip == null) { continue; }
				List<Builder> bLst = ip.getBuildersList(IMode.LOCAL_ONLY).toList();
				if (clazz != null) {
					List<Builder> bSubLst = new LinkedList<Builder>();
					for (Builder b : bLst) {
						if (b != null && clazz.isAssignableFrom(b.getClass())) {
							bSubLst.add(b);
						}
					}
					out.put(apr, bSubLst);
				} else {
					out.put(apr, bLst);
				}
			}
			return out;
		} finally {
			//re initialise with old versions map
			VersionHandler.initVersions(oldVerMap);
		}
	}

	// === PROJECT DESCRIPTOR IMPLEMENTATION ===
	
	/**
	 * Returns the {@link Descriptor} for the parent object.<br>
	 * <br>
	 * The returned object should be a class-singleton that
	 * can be used to create an instance of its parent class and thereafter
	 * display a configuration dialog.<br>
	 * As such, this class has the responsibility of creating a suitable
	 * instance, serving up the HTML/Jelly configuration fields, reading their
	 * values and modifying the created instance accordingly.<br>
	 * <br>
	 * Do note that the configuration-dialog for the object is displayed
	 * <i>after</i> the instance was created.<br>
	 */
	public DescriptorImpl getDescriptor() {
		return DESCRIPTOR;
	}

	@Extension(ordinal = 10000)
	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	public static class DescriptorImpl extends AbstractProjectDescriptor {
		private final HashSet<String> projectsToBeCreatedTransient =
				new HashSet<String>();
		
		public final static Pattern urlJobPattern = Pattern.compile("/job/([^/]+)");
		
		public DescriptorImpl() {
			//Creating the static buffers of the IP class, if necessary
			InheritanceProject.createBuffers();
		}
		
		@Override
		public String getDisplayName() {
			return Messages.InheritanceProject_DisplayName();
		}
		
		@Override
		public String getDescription() {
			return Messages.InheritanceProject_Description();
		}
		
		@Override
		public boolean isApplicable(Descriptor descriptor) {
			//If the descriptor is ArtifactAchiver, and the admin selected that it
			//should be suppressed, do not add these post-build steps
			if (ProjectCreationEngine.instance.getDisallowVanillaArchiver()) {
				if (descriptor.isSubTypeOf(ArtifactArchiver.class)) {
					return false;
				}
			}
			return super.isApplicable(descriptor);
		}
		
		@Override
		public String getCategoryId() {
			return InheritableProjectsCategory.ID;
		}
		
		@Override
		public String getIconFilePathPattern() {
			return "plugin/project-inheritance/images/:size/gear.png";
		}
		
		@Override
		public String getIconClassName() {
			return "icon-inheritance-project";
		}
		
		static {
			IconSet.icons.addIcon(new Icon("icon-inheritance-project icon-sm", "plugin/project-inheritance/images/16x16/gear.png", Icon.ICON_SMALL_STYLE));
			IconSet.icons.addIcon(new Icon("icon-inheritance-project icon-md", "plugin/project-inheritance/images/24x24/gear.png", Icon.ICON_MEDIUM_STYLE));
			IconSet.icons.addIcon(new Icon("icon-inheritance-project icon-lg", "plugin/project-inheritance/images/32x32/gear.png", Icon.ICON_LARGE_STYLE));
			IconSet.icons.addIcon(new Icon("icon-inheritance-project icon-xlg", "plugin/project-inheritance/images/48x48/gear.png", Icon.ICON_XLARGE_STYLE));
		}

		@Override
		public InheritanceProject newInstance(ItemGroup parent, String name) {
			//Checking if the given name is on the list of transient jobs
			if (this.projectsToBeCreatedTransient.contains(name)) {
				this.projectsToBeCreatedTransient.remove(name);
				return new InheritanceProject(parent, name, true);
			} else {
				return new InheritanceProject(parent, name, false);
			}
		}
		
		public ListBoxModel doFillCreationClassItems() {
			ListBoxModel names = new ListBoxModel();
			for (CreationClass cl : ProjectCreationEngine.instance.getCreationClasses()) {
				names.add(cl.name);
			}
			//And also add an empty one, to select NO mating
			names.add("<None Specified>", "");
			return names;
		}
		
		/**
		 * Wrapper around {@link DescriptorImpl#doFillCreationClassItems()} to
		 * account for slightly different field names used by
		 * {@link BuildFlowScriptAction}'s groovy scripts.
		 * 
		 * @return the content of the select box
		 */
		public ListBoxModel doFillProjectClassItems() {
			return this.doFillCreationClassItems();
		}
	
		public ListBoxModel doFillUserDesiredVersionItems() {
			ListBoxModel verBox = new ListBoxModel();
			
			InheritanceProject ip = this.getConfiguredProject();
			if (ip != null) {
				for (Version v : ip.getVersions()) {
					verBox.add(v.toString(), v.id.toString());
				}
			} else {
				log.warning("Could not fetch or resolve project name");
			}
			
			return verBox;
		}
	
		/**
		 * This method identifies the project under configuration.
		 * 
		 * It first tries to do that by asking the request itself for its
		 * ancestor; but if that is unavailable it looks at the HTTP request
		 * to check for the name of the project under configuration.
		 * It then tries to retrieve the object associated with that name.
		 * 
		 * @param req the user request
		 * @return the project under configuration by the given request
		 */
		public InheritanceProject getConfiguredProject(StaplerRequest req) {
			//Fetching the current request from the user
			if (req == null) { return null; }
			
			//Then, trying to fetch an ancestor
			InheritanceProject ip = req.findAncestorObject(
					InheritanceProject.class
			);
			if (ip != null) {
				return ip;
			}
			
			//If that failed; trying to decode the URL to get the project name
			String uri = req.getRequestURI();
			if (uri == null || uri.length() == 0) { return null; }
			Matcher m = urlJobPattern.matcher(uri);
			if (m == null || !m.find()) { return null; }
			String pName = m.group(1);
			if (pName == null || pName.length() == 0) { return null; }
			
			//Now that we have the name, we try to match it to a Project
			return InheritanceProject.getProjectByName(pName);
		}
		
		protected InheritanceProject getConfiguredProject() {
			return this.getConfiguredProject(Stapler.getCurrentRequest());
		}
		
		public synchronized void addProjectToBeCreatedTransient(String name) {
			//TODO: Do not allow this set to grow too long in case of error
			this.projectsToBeCreatedTransient.add(name);
		}
		
		public synchronized void dropProjectToBeCreatedTransient(String name) {
			this.projectsToBeCreatedTransient.remove(name);
		}
	}
}
