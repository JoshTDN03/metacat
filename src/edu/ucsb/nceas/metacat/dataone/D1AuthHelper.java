package edu.ucsb.nceas.metacat.dataone;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Level;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dataone.client.v2.CNode;
import org.dataone.client.v2.itk.D1Client;
import org.dataone.service.exceptions.InvalidRequest;
import org.dataone.service.exceptions.InvalidToken;
import org.dataone.service.exceptions.NotAuthorized;
import org.dataone.service.exceptions.NotFound;
import org.dataone.service.exceptions.NotImplemented;
import org.dataone.service.exceptions.ServiceFailure;
import org.dataone.service.types.v1.Group;
import org.dataone.service.types.v1.Identifier;
import org.dataone.service.types.v1.NodeReference;
import org.dataone.service.types.v1.NodeType;
import org.dataone.service.types.v1.Permission;
import org.dataone.service.types.v1.Person;
import org.dataone.service.types.v1.Replica;
import org.dataone.service.types.v1.Session;
import org.dataone.service.types.v1.Subject;
import org.dataone.service.types.v1.SubjectInfo;
import org.dataone.service.types.v1.util.AuthUtils;
import org.dataone.service.types.v2.Node;
import org.dataone.service.types.v2.NodeList;
import org.dataone.service.types.v2.SystemMetadata;
import org.dataone.service.types.v2.util.NodelistUtil;



/**
 * This is delegate class for D1NodeService and subclasses.
 * It centralizes authorization implementations to make them
 * more consistent across the various API methods, and more testable.
 * 
 * There are 6 basic authorization checks that can be done, and these
 * are implemented as protected methods in this class.  these checks are:
 * 1. session vs. systemMetadata subjects
 * 2. session vs. local admin credentials
 * 3. session vs. systemMetadata authoritativeMemberNode (requires NodeList)
 * 4. session vs. CN nodelist subjects (checking for CN admin authorization)
 * 5. session vs. systemMetadata replica nodeReferences (via nodelist subjects)
 * 6. session vs. expanded rightsHolder equivalent subjects and groups. (uses API calls to the CN)
 * 
 * 
 * In practice, there are currently only a handful of combinations of authorization checks being used.
 * These are represented by the public methods in this class.
 * If more combinations are ever required, they should be added as a new public method,
 * and follow the general way the other methods are implemented.
 * 
 * The combinations in use are:
 * 1. CNadmin only
 * 2. Local or AuthoritativeMN only
 * 3. Local MN or CN admin only 
 * 4. "isAuthorized" - all checks except allowing replica nodes
 * 5. "getSystemMetadata" - all checks
 * 6. "update" authorization - success depends on the local node being the authMN
 * 
 * @author rnahf
 *
 */
public class D1AuthHelper {

    private static Log logMetacat = LogFactory.getLog(D1NodeService.class);
    

    private HttpServletRequest request;
    private String notAuthorizedCode;
    private String serviceFailureCode;
    private Identifier requestIdentifier;
    private static NodeList cnList = null;
    
    /**
     * Each instance should correspond to a single request.
     * @param request
     * @param hzSystemMetadataMap
     */
//    public D1AuthorizationDelegate(HttpServletRequest request, Map<Identifier,SystemMetadata> hzSystemMetadataMap) {
//        this.request = request;
//        this.sysmetaMap = hzSystemMetadataMap;       
//    }
    
    /**
     * Each instance should correspond to a single request.
     * @param request
     * @param hzSystemMetadataMap
     */
    public D1AuthHelper(HttpServletRequest request, Identifier requestIdentifier, String notAuthorizedCode, String serviceFailureCode) {
        this.request = request;
        this.requestIdentifier = requestIdentifier;
        this.notAuthorizedCode = notAuthorizedCode;
        this.serviceFailureCode = serviceFailureCode;
    }
        
    /**
     * Performs all authorization steps used by isAuthorized.
     * Checks for accessPolicy & rightsHolder authorization, 
     * and authorizes local, authoritativeMN, and CN admins.
     * @param session
     * @param sysmeta
     * @param permission
     * @return
     * @throws ServiceFailure
     * @throws NotAuthorized
     */
    public void doIsAuthorized(Session session, SystemMetadata sysmeta, Permission permission) throws ServiceFailure, NotAuthorized
    {
        if(session != null && session.getSubject() != null) {
            logMetacat.debug("D1AuthHepler.doIsAuthorzied - the session is "+session.getSubject().getValue());
        }
        List<ServiceFailure> exceptions = new ArrayList<>();
        // most efficient step first - uses materials passed in
        if (this.isAuthorizedBySysMetaSubjects(session, sysmeta, permission)) {
            return;
        }
        // next most efficient step: checks against local node document built via system properties 
        try {
            if (this.isLocalNodeAdmin(session, null)) {
                return;
            }
        
        } catch(ServiceFailure e) {
            exceptions.add(e);
        }
        
        
        try {
            NodeList nodelist = this.getCNNodeList();

            // these all compare the session to the nodelist in some way
            if (this.isAuthoritativeMNodeAdmin(session, sysmeta.getAuthoritativeMemberNode(), nodelist)) {
                return;
            }
            if (this.isCNAdmin(session, nodelist)) {
                return;
            }
        
        } catch (ServiceFailure e) {
            exceptions.add(e);
        }

        // this makes 1 or more calls to listSubjects, so is the most expensive
        try {
            if (this.checkExpandedPermissions(session, sysmeta, permission)) {
                return;
            }
        }
        catch (ServiceFailure e) {
            exceptions.add(e);
        }
        
        if (exceptions.isEmpty()) { 
            prepareAndThrowNotAuthorized(session,requestIdentifier, permission, notAuthorizedCode); 
        } else {
            // just use the first one
            ServiceFailure sf = exceptions.get(0);
            sf.setDetail_code(serviceFailureCode);
            throw sf;
        }
    }

    /**
     * Does local and AuthMN admin authorization
     * @param session
     * @param sysmeta
     * @throws ServiceFailure
     * @throws NotAuthorized
     */
    public void doAuthoritativeMNAuthorization(Session session, SystemMetadata sysmeta)  throws ServiceFailure, NotAuthorized
    {
        if(session != null && session.getSubject() != null) {
            logMetacat.debug("D1AuthHepler.doAuthoritativeMNAuthorization - the session is "+session.getSubject().getValue());
        }
        List<ServiceFailure> exceptions = new ArrayList<>();
        
        try {
            if (this.isLocalNodeAdmin(session, null)) {
                return;
            }
        } catch (ServiceFailure e) {
            exceptions.add(e);
        }

        try {     
            NodeList nodelist = this.getCNNodeList();
            if (this.isAuthoritativeMNodeAdmin(session, sysmeta.getAuthoritativeMemberNode(), nodelist)) {
                return;
            }
        } catch (ServiceFailure e) {
            exceptions.add(e);
        }
        
        if (exceptions.isEmpty()) { 
            prepareAndThrowNotAuthorized(session,requestIdentifier, null, notAuthorizedCode); 
        } else {
            // just use the first one
            ServiceFailure sf = exceptions.get(0);
            sf.setDetail_code(serviceFailureCode);
            throw sf;
        }
    }
    
    /**
     * The locus of updates is limited to the authoritativeMN.
     * Therefore, the authorization rules are somewhat specialized:
     * <ol><li> If the update is happening on the authoritative MN, either</li>
     * <ul><li>  the session has the appropriate permission vs the systemmetadata or</li>
     *     <li>  the session represents the MN Admin Subject</li></ul>
     *  <li>If the session represents the D1 CN, it is allowed.</li></ol>
     */
    public void doUpdateAuth(Session session, SystemMetadata sysmeta, Permission permission, NodeReference localNodeId) 
            throws NotAuthorized, ServiceFailure 
    {
        if(session != null && session.getSubject() != null) {
            logMetacat.debug("D1AuthHepler.doUpdateAuth - the session is "+session.getSubject().getValue());
        }
        boolean isAuthoritiveMN = true;
               
        List<ServiceFailure> exceptions = new ArrayList<>();
        
        if (sysmeta.getAuthoritativeMemberNode().equals(localNodeId) 
                && StringUtils.isNotBlank(sysmeta.getAuthoritativeMemberNode().getValue()))
        {            
            if (this.isAuthorizedBySysMetaSubjects(session, sysmeta, permission)) {
                return;
            }     
            try {
                if (this.isLocalMNAdmin(session)) {
                    return;
                }
            }
            catch (ServiceFailure e) {
                exceptions.add(e);
            }
        
            try {
                if (this.checkExpandedPermissions(session, sysmeta, permission)) {
                    return;
                }
            }
            catch (ServiceFailure e) {
                exceptions.add(e);
            }    
        } else {
            //this is not the authoritiveMN. Generally, this update/updateSystem should fail. But cn can do that. So go to check the cns subject
            isAuthoritiveMN = false;
        }
        
        // (outside the above if statement on purpose)
        try {
            NodeList nodelist = this.getCNNodeList();
            if( this.isCNAdmin(session, nodelist) ) {
                return;
            }
        }
        catch (ServiceFailure e) {
            exceptions.add(e);
        }

        String authoritiveMNMessage = "clients can only call the update/updateSystemMetadata request on an object when it locates on its authoritative memember node. "+
               "However, the authoritative member node of the object "+sysmeta.getIdentifier().getValue()+ " on your request is "+sysmeta.getAuthoritativeMemberNode().getValue()+
               ", which is differen to the current node "+localNodeId.getValue();    
        
        if (exceptions.isEmpty()) { 
            if(isAuthoritiveMN) {
                prepareAndThrowNotAuthorized(session,requestIdentifier, permission, notAuthorizedCode); 
            } else {
                logMetacat.warn(authoritiveMNMessage);
                throw new NotAuthorized(notAuthorizedCode, authoritiveMNMessage);
            }
            
        } else {    
            for (ServiceFailure sf : exceptions) {
                logMetacat.warn("For request ["+ request+"]: ServiceFailure raised:" + sf.getDescription(),sf);
            }
            
            // just use the first one
            ServiceFailure sf = exceptions.get(0);
            sf.setDetail_code(serviceFailureCode);
            throw sf;
        }    
    }

    /**
     * Does only localNode(CN)/CN authorization
     * @param session
     * @throws ServiceFailure
     * @throws NotAuthorized
     */
    public void doCNOnlyAuthorization(Session session) throws ServiceFailure, NotAuthorized
    {
        if(session != null && session.getSubject() != null) {
            logMetacat.debug("D1AuthHepler.doCNOnlyAuthorization - the session is "+session.getSubject().getValue());
        }
        List<ServiceFailure> exceptions = new ArrayList<>();
        
        try {
            if (this.isLocalNodeAdmin(session, NodeType.CN)) {
                return;
            }
        } catch (ServiceFailure e) {
            exceptions.add(e);
        }
         
        try {
            NodeList nodelist = this.getCNNodeList();
            if (this.isCNAdmin(session, nodelist)) {
                return;
            }
        } catch (ServiceFailure e) {
            exceptions.add(e);
        }

        if (exceptions.isEmpty()) { 
            prepareAndThrowNotAuthorized(session,requestIdentifier, null, notAuthorizedCode); 
        } else {
            // just use the first one
            ServiceFailure sf = exceptions.get(0);
            sf.setDetail_code(serviceFailureCode);
            throw sf;
        }
    }

    /**
     * Does MN/CN admin authorization
     * @param session
     * @throws ServiceFailure
     * @throws NotAuthorized
     */
    public void doAdminAuthorization(Session session) throws ServiceFailure, NotAuthorized {
        if(session != null && session.getSubject() != null) {
            logMetacat.debug("D1AuthHepler.doAdminAuthorization - the session is "+session.getSubject().getValue());
        }
        List<ServiceFailure> exceptions = new ArrayList<>();

        try {
            if (this.isLocalNodeAdmin(session, null)) {
                return;
            }
        } catch (ServiceFailure e) {
            exceptions.add(e);
        }

        try {
            NodeList nodelist = this.getCNNodeList();
            if (this.isCNAdmin(session, nodelist)) {
                return;
            }
        } catch (ServiceFailure e) {
            exceptions.add(e);
        }

        if (exceptions.isEmpty()) { 
            prepareAndThrowNotAuthorized(session,requestIdentifier, null, notAuthorizedCode); 
        } else {
            // just use the first one
            ServiceFailure sf = exceptions.get(0);
            sf.setDetail_code(serviceFailureCode);
            throw sf;
        }
    }

    
    /**
     * used by getSystemMetadata, describe, and getPackage, the latter two by delegation to getSystemMetadata
     * Very similar to doIsAuthorized, but also allows replica nodes administrative access.
     * 
     * @param session
     * @param sysmeta
     * @param permission
     * @return
     * @throws ServiceFailure
     * @throws NotAuthorized
     */
    public void doGetSysmetaAuthorization(Session session, SystemMetadata sysmeta, Permission permission) throws ServiceFailure, NotAuthorized
    {      
        if(session != null && session.getSubject() != null) {
            logMetacat.debug("D1AuthHepler.doGetSysmetaAuthorization - the session is "+session.getSubject().getValue());
        }
        List<ServiceFailure> exceptions = new ArrayList<>();
        // most efficient step first - uses materials passed in
        if (this.isAuthorizedBySysMetaSubjects(session, sysmeta, permission)) {
            return;
        }
        // next most efficient step: checks against local node document built via system properties 
        try {
            if (this.isLocalNodeAdmin(session, null)) {
                return;
            }
        }
        catch(ServiceFailure e) {
            exceptions.add(e);
        }
        
        
        try {
            NodeList nodelist = this.getCNNodeList();

            // these all compare the session to the nodelist in some way
            if (this.isAuthoritativeMNodeAdmin(session, sysmeta.getAuthoritativeMemberNode(), nodelist)) {
                return;
            }
            if (this.isCNAdmin(session, nodelist)) {
                return;
            }
            if (this.isReplicaMNodeAdmin(session, sysmeta, nodelist)) {
                return;
            }
        }
        catch (ServiceFailure e) {
            exceptions.add(e);
        }

        // this makes 1 or more calls to listSubjects, so is the most expensive
        try {
            if (this.checkExpandedPermissions(session, sysmeta, permission)) {
                return;
            }
        }
        catch (ServiceFailure e) {
            exceptions.add(e);
        }
        
        if (exceptions.isEmpty()) { 
            prepareAndThrowNotAuthorized(session,requestIdentifier, permission, notAuthorizedCode); 
        } else {
             ServiceFailure sf = exceptions.get(0);
             sf.setDetail_code(serviceFailureCode);
             throw sf;
        }
    }
    
 
    
    
    protected void prepareAndThrowNotAuthorized(Session session, Identifier pid, Permission permission, String detailCode) throws NotAuthorized {
        
        Set<Subject> sessionSubjects = AuthUtils.authorizedClientSubjects(session);
        StringBuffer includedSubjects = new StringBuffer();
        for (Subject s: sessionSubjects) {
            includedSubjects.append(s.getValue() + "; ");
        } 
        
        String msg = String.format(
                "%s not allowed on %s for subject[s]: %s",
                    permission == null ? "Permission" : permission,
                    pid == null ? null : pid.getValue(),
                    includedSubjects.toString()
                    );
        logMetacat.warn(msg);
        throw new NotAuthorized(detailCode, msg);   
    }
    
    
    /**
     * Compare all the session subjects against the expanded subjects (from listSubjects)
     * of the object rightsholder.
     * @param sessionSubjects
     * @param sysmeta
     * @param permission
     * @return true or false, depending...
     * @throws ServiceFailure
     */
    protected boolean checkExpandedPermissions(Session session, SystemMetadata sysmeta, Permission permission) throws ServiceFailure {
            
        // TODO:  Is getting the subjectInfo of the rightsHolder really necessary? or do we need to fix getSubjectInfo 
        // so we don't have to go back to the CNIdentity service to resolve ownership?  (This was put in to solve 
        // nested groups transitivity problems, to the best of my knowledge.)
        boolean isAllowed = false;
        try {
            Set<Subject> sessionSubjects = AuthUtils.authorizedClientSubjects(session);
            for (Subject s : sessionSubjects) {
                if (s.getValue().equalsIgnoreCase("public"))  {
                    //assume the special subject 'public' isn't up for expansion
                    continue;
                }

                if (D1AuthHelper.expandRightsHolder(sysmeta.getRightsHolder(), s)) {  // expensive call to listSubjects
                    isAllowed = true;
                    break;
                }
            }          
        } 
        catch (NotImplemented | InvalidRequest | InvalidToken e) {
            ServiceFailure sf = new ServiceFailure("1030", "Exception thrown from expandRightsHolder(): " 
                    + e.getClass().getCanonicalName() + ":: " + e.getDescription());
            sf.initCause(e);
            throw sf; 
        } 
        catch (NotAuthorized e) {
            isAllowed = false;
        }
        return isAllowed;
    }
   
    
    
    
    
    
    /**
     * A centralized point for accessing the CN Nodelist,
     * to make it easier to cache the nodelist in the future,
     * if it's seen as helpful performance-wise
     * @return
     * @throws ServiceFailure
     * @throws NotImplemented
     */
    protected NodeList getCNNodeList() throws ServiceFailure {
        if (cnList != null && cnList.getNodeList() != null && cnList.getNodeList().size() >0) {
            logMetacat.debug("D1AuthHelper.getCNNodeList - got the cn list from the cache.");
            return cnList;
        } else {
            // are we allowed to do this? only CNs are allowed
            try {
                CNode cn = D1Client.getCN();
                logMetacat.debug("D1AuthHelper.getCNNodeList - got CN instance and get the cn list from the network.");
                cnList = cn.listNodes();
                return cnList; 
            } catch (NotImplemented e) {
                logMetacat.error("Unexpected Error getting NodeList from getCNNodeList().  Got 'NotImplemented' from the service call!",e);
                throw new ServiceFailure("","Could not get NodeList from the CN. got 'NotImplemented' from the service call!");
            }
        }
       
    }
    
    /**
     * Check if the given userSession is the member of the right holder group (if the right holder is a group subject).
     * If the right holder is not a group, it will be false of course.
     * @param rightHolder the subject of the right holder.
     * @param sessionSubject the subject will be compared
     * @return true if the user session is a member of the right holder group; false otherwise.
     * @throws NotImplemented 
     * @throws ServiceFailure 
     * @throws NotAuthorized 
     * @throws InvalidToken 
     * @throws InvalidRequest 
     */
    public static boolean expandRightsHolder(Subject rightHolder, Subject sessionSubject) 
        throws ServiceFailure, NotImplemented, InvalidRequest, NotAuthorized, InvalidToken 
    {
        // public and static because it is used outside of D1NodeService and subclasses - PermissionController
        boolean is = false;
        if(rightHolder != null && sessionSubject != null && rightHolder.getValue() != null && !rightHolder.getValue().trim().equals("") && sessionSubject.getValue() != null && !sessionSubject.getValue().trim().equals("")) {
            CNode cn = D1Client.getCN();
            logMetacat.debug("D1AuthorizationDelegate.expandRightHolder - at the start of method: after getting the cn node and cn node is "+cn.getNodeBaseServiceUrl());
            String query= rightHolder.getValue();
            int start =0;
            int count= 200;
            String status = null;
            Session session = null;
            SubjectInfo subjects = cn.listSubjects(session, query, status, start, count);

            while(subjects != null) {
                logMetacat.debug("D1AuthorizationDelegate.expandRightHolder - search the subject "+query+" in the cn and the returned result is not null");
                List<Group> groups = subjects.getGroupList();
                is = isInGroups(sessionSubject, rightHolder, groups);
                if(is) {
                    //since we find it, return it.
                    return is;
                } else {
                    //decide if we need to try the page query for another trying.
                    int sizeOfGroups = 0;
                    if(groups != null) {
                       sizeOfGroups  = groups.size();
                    }
                    List<Person> persons = subjects.getPersonList();
                    int sizeOfPersons = 0;
                    if(persons != null) {
                        sizeOfPersons = persons.size();
                    }
                    int totalSize = sizeOfGroups+sizeOfPersons;
                    //logMetacat.debug("D1NodeService.expandRightHolder - search the subject "+query+" in the cn and the size of return result is "+totalSize);
                   //we can't find the target on the first query, maybe query again.
                    if(totalSize == count) {
                        start = start+count;
                        logMetacat.debug("D1AuthorizationDelegate.expandRightHolder - search the subject "+query+" in the cn and the size of return result equals the count "+totalSize+" .And we didn't find the target in the this query. So we have to use the page query with the start number "+start);
                        subjects = cn.listSubjects(session, query, status, start, count);
                    } else if (totalSize < count){
                        logMetacat.debug("D1AuthorizationDelegate.expandRightHolder - we are already at the end of the returned restult since the size of returned results "+totalSize+
                            " is less than the count "+count+". So we have to break the loop and finish the try.");
                        break;
                    } else if (totalSize >count) {
                        logMetacat.warn("D1AuthorizationDelegate.expandRightHolder - Something is wrong on the implementation of the method listSubject since the size of returned results "+totalSize+
                                " is greater than the count "+count+". So we have to break the loop and finish the try.");
                        break;
                    }
                }
                
            } 
            //logMetacat.debug("D1NodeService.expandRightHolder - search the subject "+query+" in the cn and the returned result is null");
            if(!is) {
                logMetacat.debug("D1AuthorizationDelegate.expandRightHolder - We can NOT find any member in the group "+query+" (if it is a group) matches the user "+sessionSubject.getValue());
            }
        } else {
            logMetacat.debug("D1AuthorizationDelegate.expandRightHolder - We can't determine if the use subject is a member of the right holder group since one of them is null or blank");
        }
       
        return is;
    }
    
    /*
     * If the given useSession is a member of a group which is in the given list of groups and has the name of righHolder.
     */
    private static boolean isInGroups(Subject userSession, Subject rightHolder, List<Group> groups) {
        boolean is = false;
        if(groups != null) {
            logMetacat.debug("D1NodeService.isInGroups -  the given groups' (the returned result including groups) size is "+groups.size());
            for(Group group : groups) {
                //logMetacat.debug("D1NodeService.expandRightHolder - group has the subject "+group.getSubject().getValue());
                if(group != null && group.getSubject() != null && group.getSubject().equals(rightHolder)) {
                    logMetacat.debug("D1NodeService.isInGroups - there is a group in the list having the subjecct "+group.getSubject().getValue()+" which matches the right holder's subject "+rightHolder.getValue());
                    List<Subject> members = group.getHasMemberList();
                    if(members != null ){
                        logMetacat.debug("D1NodeService.isInGroups - the group "+group.getSubject().getValue()+" in the cn has members");
                        for(Subject member : members) {
                            logMetacat.debug("D1NodeService.isInGroups - compare the member "+member.getValue()+" with the user "+userSession.getValue());
                            if(member.getValue() != null && !member.getValue().trim().equals("") && userSession.getValue() != null && member.getValue().equals(userSession.getValue())) {
                                logMetacat.debug("D1NodeService.isInGroups - Find it! The member "+member.getValue()+" in the group "+group.getSubject().getValue()+" matches the user "+userSession.getValue());
                                is = true;
                                return is;
                            }
                        }
                    }
                    break;//we found the group but can't find the member matches the user. so break it.
                }
            }
        } else {
            logMetacat.debug("D1NodeService.isInGroups -  the given group is null (the returned result does NOT have a group");
        }
        return is;
    }

    /**
     * Test if the user identified by the provided token has administrative authorization 
     * on this node because they are calling themselves
     * (the implementation uses property Settings to build a Node instance)
     * 
     * @param session - the Session object containing the credentials for the Subject
     * 
     * @return true if the user is this node
     * @throws ServiceFailure 
     * @throws NotImplemented 
     */
    
    public boolean isLocalMNAdmin(Session session) throws ServiceFailure {
        return isLocalNodeAdmin(session, NodeType.MN);
    }
    
    /**
     * Test if the user identified by the provided token has administrative authorization 
     * on this node because they are calling themselves
     * (the implementation uses property Settings to build a Node instance)
     * 
     * @param session - the Session object containing the credentials for the Subject
     * 
     * @return true if the user is this node
     * @throws ServiceFailure 
     * @throws NotImplemented 
     */
    public boolean isLocalCNAdmin(Session session) throws ServiceFailure {
        return isLocalNodeAdmin(session, NodeType.CN);
    }
/////////////  /////////////  
//implementations         //
/////////////  /////////////  
       
       
    /**
     * Checks Metacat properties representing the local Node document for
     * matching Node.subjects.  The NodeType parameter can be set to limit this 
     * authorization check if needed.
     * @param session
     * @param nodeType  
     * @return
     * @throws ServiceFailure
     */
    protected boolean isLocalNodeAdmin(Session session, NodeType nodeType) throws ServiceFailure {

        boolean allowed = false;
        
        // must have a session in order to check admin 
        if (session == null) {
           logMetacat.debug("In isLocalNodeAdmin(), session is null ");
           return false;
        }
        
        logMetacat.debug("In isLocalNodeAdmin(), MN authorization for the user " +
             session.getSubject().getValue());
        
        Node node = MNodeService.getInstance(request).getCapabilities();
        NodeReference nodeReference = node.getIdentifier();
        logMetacat.debug("In isLocalNodeAdmin(), Node reference is: " + nodeReference.getValue());
        
        Set<Subject> sessionSubjects = AuthUtils.authorizedClientSubjects(session);
        
        if (nodeType == null || node.getType() == nodeType) {
            List<Subject> nodeSubjects = node.getSubjectList();
            if (sessionSubjects != null) {
              outer:
                for (Subject subject : sessionSubjects) {
                    // check if the session subject is in the node subject list
                    for (Subject nodeSubject : nodeSubjects) {
                        logMetacat.debug("In isLocalNodeAdmin(), comparing subjects: " +
                            nodeSubject.getValue() + " and the user" + subject.getValue());
                        if ( nodeSubject.equals(subject) ) {
                            allowed = true; // subject of session == this node's subect
                            break outer;
                        }
                    }            
                }
            }
        }
        logMetacat.debug("In is isLocalNodeAdmin method. Is this a local node admin? "+allowed);
        return allowed;
    }
    
    

    
    
    /**
     * Returns the authorization status of the Session vs. the given SystemMetadata 
     * based on the rightsHolder and AccessPolicy fields
     * @param session
     * @param sysmeta
     * @param permission
     * @return true|false
     */
    protected boolean isAuthorizedBySysMetaSubjects(Session session, SystemMetadata sysmeta, Permission permission) {

        // get the subject[s] from the session
        // defer to the shared util for recursively compiling the subjects   
        Set<Subject> sessionSubjects = AuthUtils.authorizedClientSubjects(session);
        
        if(logMetacat.isDebugEnabled()) {
            if(sessionSubjects != null) {
                for(Subject subject : sessionSubjects) {
                    logMetacat.debug("=================== The equalvent subject is "+subject.getValue());
                }
            } 
        }

        if (AuthUtils.isAuthorized(sessionSubjects, permission, sysmeta)) {
            return true;
        }

        return false;
    }

    
    
    
    /**
     * determines if the session represents a replicaMN of the given systemMetadata.
     * @param session - the session, uses only the session.subject field
     * @param sysmeta
     * @param nodelist
     * @return true|false
     */
    protected boolean isReplicaMNodeAdmin(Session session, SystemMetadata sysmeta, NodeList nodelist) {

        boolean isAuthorized = false;

        Subject subject = session == null ? null : session.getSubject();
        List<Replica> replicaList = sysmeta.getReplicaList();
        NodeReference replicaNodeRef = null;

        if  ( replicaList != null && subject != null ) {
            // get the list of nodes with a matching node subject
            Set<Node> nodeListBySubject = NodelistUtil.selectNode(nodelist, subject);

            if (nodeListBySubject.size() > 0) {
                // compare node ids to replica node ids
                outer: 
                    for (Replica replica : replicaList) {
                        replicaNodeRef = replica.getReplicaMemberNode();

                        for (Node node : nodeListBySubject) {
                            if (node.getIdentifier().equals(replicaNodeRef)) {
                                // node id via session subject matches a replica node
                                isAuthorized = true;
                                break outer;
                            }
                        }
                    }
            }
        }
        return isAuthorized;
    }

    
    /**
     * Compare the session.subject to the authoritativeMN Node.nodeSubjects list of Subjects.
     * According the the DataONE documentation, the authoritative member node has all the 
     * rights of the *rightsHolder*.
     * Any null parameter will result in return of false
     * 
     * @param session
     * @param authoritativeMNode
     * @param nodelist
     * @return
     */
    protected boolean isAuthoritativeMNodeAdmin(Session session, NodeReference authoritativeMNode, NodeList nodelist) { 
        
        boolean allowed = false;

        if (session == null) {
            return false;
        }
        if (authoritativeMNode == null) {
            return false;
        }
        if (nodelist == null) {
            return false;
        }
        

        Set<Subject> sessionSubjects = AuthUtils.authorizedClientSubjects(session);
        if (sessionSubjects == null) {
            return false;
        }
        Node node = NodelistUtil.findNode(nodelist, authoritativeMNode);
        if (node == null) {
            return false;
        }

        List<Subject> nodeSubjects = node.getSubjectList();
        if(nodeSubjects != null) {

            // check if the session subject is in the node subject list
          outer:
            for (Subject nodeSubject : nodeSubjects) {
                for (Subject sessionSubject : sessionSubjects) {
                    logMetacat.debug("D1NodeService.isAuthoritativeMNodeAdmin(), comparing subjects: " +
                            nodeSubject.getValue() + " and " + sessionSubject.getValue());
                    if ( nodeSubject != null && nodeSubject.equals(sessionSubject) ) {
                        allowed = true; // subject of session == target node subject
                        break outer;
                    }
                }
            }              
        }
        return allowed;
    }  
    
    /** 
     * compares session.subject against CN.NodeList
     * @param session
     * @param nodelist
     */
    protected boolean isCNAdmin(Session session, NodeList nodelist) {

        boolean allowed = false;

        logMetacat.debug("D1NodeService.isCNAdmin - the beginning");

        if (session == null || session.getSubject() == null) {
            return false;
        }
        if (nodelist == null) {
            return false;
        }
        
        List<Node> nodes = nodelist.getNodeList();

        if (nodes == null || nodes.size() == 0) {
            return false;
        }
       
        Set<Subject> sessionSubjects = AuthUtils.authorizedClientSubjects(session);
        // find the node in the node list
        search: 
            for ( Node node : nodes ) {

                NodeReference nodeReference = node.getIdentifier();
                if (logMetacat.isDebugEnabled()) {
                    logMetacat.debug("In isCNAdmin(), a Node reference from the CN node list is: " + nodeReference.getValue());
                }

                if (node.getType() == NodeType.CN) {
                    List<Subject> nodeSubjects = node.getSubjectList();

                    // check if the session subject is in the node subject list
                    for (Subject nodeSubject : nodeSubjects) {
                        if(sessionSubjects != null) {
                            for (Subject subject : sessionSubjects) {
                                if (logMetacat.isDebugEnabled()) {
                                    logMetacat.debug("In isCNAdmin(), comparing subjects: " +
                                            nodeSubject.getValue() + " and the user " + subject.getValue());
                                }
                                if ( nodeSubject.equals(subject) ) {
                                    allowed = true; // subject of session == target node subject
                                    break search;
                                }
                            }
                        }
                        
                    }              
                }
            }
        if (logMetacat.isDebugEnabled()) {
            logMetacat.debug("D1NodeService.isCNAdmin. Is it a cn admin? "+allowed);
        }
        return allowed;
    }
}
