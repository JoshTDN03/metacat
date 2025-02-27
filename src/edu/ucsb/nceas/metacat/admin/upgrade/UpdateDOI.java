/**
 *  '$RCSfile$'
 *  Copyright: 2013 Regents of the University of California and the
 *             National Center for Ecological Analysis and Synthesis
 *
 *   '$Author$'
 *     '$Date$'
 * '$Revision$'
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.ucsb.nceas.metacat.admin.upgrade;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dataone.service.types.v1.Identifier;
import org.dataone.service.types.v2.Node;
import org.dataone.service.types.v2.SystemMetadata;

import edu.ucsb.nceas.metacat.DocumentImpl;
import edu.ucsb.nceas.metacat.IdentifierManager;
import edu.ucsb.nceas.metacat.admin.AdminException;
import edu.ucsb.nceas.metacat.dataone.MNodeService;
import edu.ucsb.nceas.metacat.dataone.hazelcast.HazelcastService;
import edu.ucsb.nceas.metacat.doi.DOIServiceFactory;
import edu.ucsb.nceas.metacat.util.DocumentUtil;



/**
 * Updates existing DOI registrations for EML versions
 * @author leinfelder, walker
 *
 */
public class UpdateDOI implements UpgradeUtilityInterface {
    
	private static Log log = LogFactory.getLog(UpdateDOI.class);

	private int serverLocation = 1;
	private String nodeId = null;
	private String DOISCHEME = "doi:";
	
	/**
	 * Public constructor
	 * @throws Exception
	 */
	public UpdateDOI() throws Exception{
	    Node node = MNodeService.getInstance(null).getCapabilities();
	    nodeId = node.getIdentifier().getValue();
	}

	public int getServerLocation() {
		return serverLocation;
	}

	public void setServerLocation(int serverLocation) {
		this.serverLocation = serverLocation;
	}
	
	/** 
	 * Update the registration of a list of DOIs
	 * @param identifiers - DOIs to update
	 */
	private void updateDOIRegistration(List<String> identifiers) {
		for (String pid: identifiers) {
			try {
				//Create an identifier and retrieve the SystemMetadata for this guid
				Identifier identifier = new Identifier();
				identifier.setValue(pid);
				SystemMetadata sysMeta = HazelcastService.getInstance().getSystemMetadataMap().get(identifier);
				if(sysMeta == null) {
				    //The identifier can be a sid, so the sysMeta can be null. we need to check if it is a sid.
				    Identifier sid = new Identifier();
				    sid.setValue(pid);
				    Identifier head = IdentifierManager.getInstance().getHeadPID(sid);
				    if(head != null) {
				        sysMeta= HazelcastService.getInstance().getSystemMetadataMap().get(head);
				    }
				}
				
				//Update the registration
				if(sysMeta != null) {
				    DOIServiceFactory.getDOIService().registerDOI(sysMeta);
				}
			} catch (Exception e) {
				// what to do? nothing
				e.printStackTrace();
				continue;
			}
			
		}
	}
	
	/**
	 * Update the DOI registration of all ids in this server with EML formatIds
	 */
    public boolean upgrade() throws AdminException {
        boolean success = true;
        try {
        	// get only local ids for this server
            List<String> idList = null;
            
            idList = IdentifierManager.getInstance().getGUIDs(DocumentImpl.EML2_0_0NAMESPACE, nodeId, DOISCHEME);
            //Collections.sort(idList);
            updateDOIRegistration(idList);
            
            idList = IdentifierManager.getInstance().getGUIDs(DocumentImpl.EML2_0_1NAMESPACE, nodeId, DOISCHEME);
            //Collections.sort(idList);
            updateDOIRegistration(idList);
            
            idList = IdentifierManager.getInstance().getGUIDs(DocumentImpl.EML2_1_0NAMESPACE, nodeId, DOISCHEME);
            //Collections.sort(idList);
            updateDOIRegistration(idList);
            
            idList = IdentifierManager.getInstance().getGUIDs(DocumentImpl.EML2_1_1NAMESPACE, nodeId, DOISCHEME);
            //Collections.sort(idList);
            updateDOIRegistration(idList);
            
            idList = IdentifierManager.getInstance().getGUIDs(DocumentImpl.EML2_2_0NAMESPACE, nodeId, DOISCHEME);
            updateDOIRegistration(idList);
            
		} catch (Exception e) {
			String msg = "Problem updating DOIs: " + e.getMessage();
			log.error(msg, e);
			success = false;
			throw new AdminException(msg);
		}
        
        
        return success;
    }
    
	/**
	 * Update the registration of all DOIs with the specified guids in this server
	 * @param ids - a List of DOIs to update
	 */
    public boolean upgradeById(List<String> ids) throws AdminException {
        boolean success = true;
        
        try{
        	updateDOIRegistration(ids);
	    } catch (Exception e) {
			String msg = "Problem updating DOIs: " + e.getMessage();
			log.error(msg, e);
			success = false;
			throw new AdminException(msg);
		}
        return success;
    }
    
    /**
     * Update the registration of all DOIs in this server with the specified formatId
     * @param formatIds - a List of formatIDs used to filter DOI selection 
     */
    public boolean upgradeByFormatId(List<String> formatIds) throws AdminException {
        boolean success = true;  
        try{
        	for (String formatId: formatIds) {        		
	        	//Get all the docids with this formatId
        		List<String> idList = IdentifierManager.getInstance().getGUIDs(formatId, nodeId, DOISCHEME);
	        	//Update the registration for all these guids
	        Collections.sort(idList);
	        updateDOIRegistration(idList);
        	}
	    } catch (Exception e) {
			String msg = "Problem updating DOIs: " + e.getMessage();
			log.error(msg, e);
			success = false;
			throw new AdminException(msg);
		}
        return success;
    }
    
}
