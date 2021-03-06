package com.dotcms.publisher.business;

import com.dotcms.publisher.bundle.bean.Bundle;
import com.dotcms.publisher.business.PublishAuditStatus.Status;
import com.dotcms.publisher.environment.bean.Environment;
import com.dotcms.publisher.mapper.PublishQueueMapper;
import com.dotcms.publisher.util.PublisherUtil;
import com.dotmarketing.beans.Identifier;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.PermissionAPI;
import com.dotmarketing.cms.factories.PublicCompanyFactory;
import com.dotmarketing.common.db.DotConnect;
import com.dotmarketing.db.DbConnectionFactory;
import com.dotmarketing.db.HibernateUtil;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotHibernateException;
import com.dotmarketing.portlets.folders.model.Folder;
import com.dotmarketing.portlets.structure.factories.StructureFactory;
import com.dotmarketing.portlets.structure.model.Structure;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.PushPublishLogger;
import com.dotmarketing.util.UtilMethods;
import com.liferay.portal.model.Company;
import com.liferay.portal.model.User;

import java.util.*;

/**
 * Implement the PublishQueueAPI abstract class methods
 * @author Oswaldo
 *
 */
public class PublisherAPIImpl extends PublisherAPI{

	private PublishQueueMapper mapper = null;

	private static PublisherAPIImpl instance= null;

	public static PublisherAPIImpl getInstance() {
		if(instance==null){
			instance = new PublisherAPIImpl();
		}

		return instance;
	}
	protected PublisherAPIImpl(){
		mapper = new PublishQueueMapper();
	}

	private static final String MANDATORY_FIELDS=
										"operation, "+
										"asset, "+
										"entered_date, "+
										"language_id, "+
										"publish_date, "+
										"type, "+
										"bundle_id ";
	private static final String MANDATORY_PLACE_HOLDER = "?,?,?,?,?,?,?" ;

//
	//"last_results, "+
	//	"last_try,  "+
	//	"num_of_tries, "+

	private static final String INSERTSQL="insert into publishing_queue("+MANDATORY_FIELDS+") values("+MANDATORY_PLACE_HOLDER+")";

    /**
     * Prepare the given assets to be published adding them to the Publishing queue
     *
     * @param identifiers
     * @param bundleId
     * @param publishDate
     * @param user
     * @throws DotPublisherException
     */
    public Map<String, Object> addContentsToPublish ( List<String> identifiers, String bundleId, Date publishDate, User user ) throws DotPublisherException {
    	return addAssetsToQueue(identifiers, bundleId, publishDate, user, ADD_OR_UPDATE_ELEMENT);
    }

    /**
     * Prepare the given assets to be unpublished adding them to the Publishing queue
     *
     * @param identifiers
     * @param bundleId
     * @param unpublishDate
     * @param user
     * @throws DotPublisherException
     */
    public Map<String, Object> addContentsToUnpublish ( List<String> identifiers, String bundleId, Date unpublishDate, User user ) throws DotPublisherException {
    	return addAssetsToQueue(identifiers, bundleId, unpublishDate, user, DELETE_ELEMENT);
    }


    @Override
	public Map<String, Object> saveBundleAssets(List<String> identifiers, String bundleId,
			User user) throws DotPublisherException {
    	return addAssetsToQueue(identifiers, bundleId, null, user, -1);
	}

    /**
     * Adds a list of given identifiers to the Push Publish Queue,
     *
     * @param identifiers   Identifiers to add to the Push Publish Queue
     * @param bundleId      The id of the bundle the assets will be part of
     * @param operationDate When to apply the operation
     * @param user          current user
     * @param operationType Publish/Un-publish
     * @return A map with the results of the operation, this map contains: the total number of assets we tried to add (<strong>total</strong>)<br/>
     *         the number of failed assets (<strong>errors</strong> -> Permissions problems) and an ArrayList of error messages for the failed assets (<strong>errorMessages</strong>)<br/>
     *         <strong>Keys: total, errors, errorMessages</strong>
     * @throws DotPublisherException
     */
    private Map<String, Object> addAssetsToQueue(List<String> identifiers, String bundleId, Date operationDate, User user, long operationType ) throws DotPublisherException {

        //Map to store the results and errors adding Assets to que Queue
        Map<String, Object> resultMap = new HashMap<String, Object>();
        List<String> errorsList = new ArrayList<String>();

    	  if ( identifiers != null ) {

    		  boolean localTransaction = false;

    		  try {
    			  localTransaction = HibernateUtil.startLocalTransactionIfNeeded();
    		  } catch(DotDataException dde) {
    			  throw new DotPublisherException("Error starting Transaction", dde);
    		  }

              try {
                  for ( String identifier : identifiers ) {

                      DotConnect dc = new DotConnect();
                      dc.setSQL( INSERTSQL );
                      PermissionAPI strPerAPI = APILocator.getPermissionAPI();

                      String type = "";

                      //First verify what kind of element we want to publish in order to avoid unnecessary calls
                      if ( identifier.contains( "user_" ) ) {//Trying to publish a user
                          type = "user";
                      } else if ( identifier.contains( ".jar" ) ) {//Trying to publish an OSGI jar bundle
                          type = "osgi";
                      } else {

                          Identifier iden = APILocator.getIdentifierAPI().find( identifier );

                          if ( !UtilMethods.isSet( iden.getId() ) ) { // we have an inode, not an identifier
                              try {
                                  // check if it is a structure
                                  Structure st = null;
                                  List<Structure> sts = StructureFactory.getStructures();
                                  for ( Structure s : sts ) {
                                      if ( s.getInode().equals( identifier ) ) {
                                          st = s;
                                      }
                                  }
                                  Folder folder;

                                  /**
                                   * ISSUE 2244: https://github.com/dotCMS/dotCMS/issues/2244
                                   *
                                   */
                                  // check if it is a category
                                  if ( CATEGORY.equals( identifier ) ) {
                                      type = "category";
                                  } else if ( UtilMethods.isSet( st ) ) {
                                      if ( !strPerAPI.doesUserHavePermission( st, PermissionAPI.PERMISSION_PUBLISH, user ) ) {
                                          //Generate and append the error message
                                          appendPermissionError( errorsList, user, "Structure", st.getName(), st.getIdentifier() );
                                          continue;
                                      }

                                      type = "structure";
                                  }

                                  // check if it is a folder
                                  else if ( UtilMethods.isSet( folder = APILocator.getFolderAPI().find( identifier, user, false ) ) ) {
                                      if ( !strPerAPI.doesUserHavePermission( folder, PermissionAPI.PERMISSION_PUBLISH, user ) ) {
                                          //Generate and append the error message
                                          appendPermissionError( errorsList, user, "Folder", folder.getName(), folder.getIdentifier() );
                                          continue;
                                      }

                                      type = "folder";
                                  }
                              } catch ( Exception ex ) {
                            	  if ( UtilMethods.isSet( APILocator.getWorkflowAPI().findScheme(identifier) )) {
                                	  type = "workflow";
                                  }
                              }

                          } else {
                              if ( !strPerAPI.doesUserHavePermission( iden, PermissionAPI.PERMISSION_PUBLISH, user ) ) {
                                  //Generate and append the error message
                                  appendPermissionError( errorsList, user, iden.getAssetType(), null, iden.getId() );
                                  continue;
                              }
                              type = UtilMethods.isSet( APILocator.getHostAPI().find( identifier, user, false ) ) ? "host" : iden.getAssetType();
                          }
                      }

                      String action = operationType==ADD_OR_UPDATE_ELEMENT?"Publish":operationType==DELETE_ELEMENT?"Delete":"Added by Browsing";
                      dc.addParam( operationType );

                      dc.addObject( identifier ); //asset
                      dc.addParam( new Date() ); // entered date
                      dc.addObject( 1 ); // language id

                      if(UtilMethods.isSet(operationDate)) {
                    	  dc.addParam( operationDate );
                      } else {
                    	  dc.addObject(null);
                      }

                      dc.addObject( type );
                      dc.addObject( bundleId );

                      dc.loadResult();


                      PushPublishLogger.log(getClass(), "Asset added to Push Publish Queue. Action: "+action+", Asset Type: " + type + ", Asset Id: " + identifier, bundleId, user);
                  }

                  if(localTransaction) {
                      HibernateUtil.commitTransaction();
                  }
              } catch ( Exception e ) {
                  if(localTransaction) {
                      try {
                          HibernateUtil.rollbackTransaction();
                      } catch ( DotHibernateException e1 ) {
                          Logger.error( PublisherAPIImpl.class, e.getMessage(), e1 );
                      }
                  }
                  Logger.error( PublisherAPIImpl.class, e.getMessage(), e );
                  throw new DotPublisherException( "Unable to add element to publish queue table:" + e.getMessage(), e );
              }
          }

        //Preparing and returning the response status object
        resultMap.put( "errorMessages", errorsList );
        resultMap.put( "errors", errorsList.size() );
        resultMap.put( "bundleId", bundleId );
        resultMap.put( "total", identifiers != null ? identifiers.size() : 0 );
        return resultMap;
    }

    /**
     * Generate and append Permissions error messages
     *
     * @param errorsList
     * @param user
     * @param assetType
     * @param assetName
     * @param identifier
     */
    private void appendPermissionError ( List<String> errorsList, User user, String assetType, String assetName, String identifier ) {

        //First we should get the authentication type for this company
        Company company = PublicCompanyFactory.getDefaultCompany();
        String authType = company.getAuthType();

        String userData;
        if ( authType.equals( Company.AUTH_TYPE_ID ) ) {
            userData = user.getUserId();
        } else {
            userData = user.getEmailAddress();
        }

        if (assetName == null) {
            assetName = PublishAuditUtil.getInstance().getTitle( assetType, identifier );
        }

        //Generate and append the error message
        String errorMessage = "User: " + userData + " does not have Publish Permission over " + assetType + ": " + identifier;//For logs
        Logger.warn( PublisherAPIImpl.class, errorMessage );
        errorMessage = "User: " + userData + " does not have Publish Permission over " + assetType + ": " + assetName;//For user
        errorsList.add( errorMessage );
    }

    private static final String TREE_QUERY = "select * from tree where child = ? or parent = ?";
	/**
	 * Get tree data of a content
	 * @param indentifier
	 * @return
	 * @throws DotPublisherException
	 */
	public List<Map<String,Object>> getContentTreeMatrix(String id) throws DotPublisherException {
		List<Map<String,Object>> res = null;
		DotConnect dc=new DotConnect();
		dc.setSQL(TREE_QUERY);
		dc.addParam(id);
		dc.addParam(id);

		try {
			res = dc.loadObjectResults();
		} catch (Exception e) {
			Logger.error(PublisherAPIImpl.class,e.getMessage(),e);
			throw new DotPublisherException("Unable find tree:" + e.getMessage(), e);
		}
		return res;
	}


	private static final String MULTI_TREE_QUERY = "select multi_tree.* from multi_tree join htmlpage_version_info on htmlpage_version_info.identifier = multi_tree.parent1 join container_version_info on container_version_info.identifier = multi_tree.parent2 join contentlet_version_info on contentlet_version_info.identifier = multi_tree.child where multi_tree.child = ? and htmlpage_version_info.deleted = ? and container_version_info.deleted = ? and contentlet_version_info.deleted = ?";
	/**
	 * Get multi tree data of a content
	 * @param indentifier
	 * @return
	 */
	public List<Map<String,Object>> getContentMultiTreeMatrix(String id) throws DotPublisherException {
		List<Map<String,Object>> res = null;
		DotConnect dc=new DotConnect();
		dc.setSQL(MULTI_TREE_QUERY);
		dc.addParam(id);
		dc.addParam(Boolean.FALSE);
		dc.addParam(Boolean.FALSE);
		dc.addParam(Boolean.FALSE);
		try {
			res = dc.loadObjectResults();
		} catch (Exception e) {
			Logger.error(PublisherAPIImpl.class,e.getMessage(),e);
			throw new DotPublisherException("Unable find multi tree:" + e.getMessage(), e);
		}

		return res;
	}

	private static final String GETENTRIESBYSTATUS =
			"SELECT a.bundle_id, p.entered_date, p.asset, a.status, p.operation "+
			"FROM publishing_queue p, publishing_queue_audit a "+
			"where p.bundle_id = a.bundle_id "+
			"and a.status = ? ";

	public List<Map<String,Object>> getQueueElementsByStatus(Status status) throws DotPublisherException {
		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(GETENTRIESBYSTATUS);

			dc.addParam(status.getCode());

			return dc.loadObjectResults();
		}catch(Exception e){
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to get list of elements with error:"+e.getMessage(), e);
		}
	}

	private static final String GETENTRIES =
			"SELECT * "+
			"FROM publishing_queue p order by bundle_id ";

	public List<PublishQueueElement> getQueueElements() throws DotPublisherException {
		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(GETENTRIES);
			return mapper.mapRows(dc.loadObjectResults());
		}catch(Exception e){
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to get list of elements with error:"+e.getMessage(), e);
		}finally{
			DbConnectionFactory.closeConnection();
		}
	}

	private static final String COUNTENTRIES="select count(*) as count from publishing_queue ";

	public Integer countQueueElements() throws DotPublisherException {
		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(COUNTENTRIES);

			return Integer.parseInt(dc.loadObjectResults().get(0).get("count").toString());
		}catch(Exception e){
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to get list of elements with error:"+e.getMessage(), e);
		}finally{
			DbConnectionFactory.closeConnection();
		}
	}

	private static final String ETENTRIESGROUPED=
			"SELECT a.bundle_id, p.entered_date, a.status, p.operation " +
			"FROM publishing_queue p, publishing_queue_audit a " +
			"where p.bundle_id = a.bundle_id group by bundle_id ";


	public List<Map<String,Object>> getQueueElementsGroupByBundleId() throws DotPublisherException {
		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(ETENTRIESGROUPED);
			return dc.loadObjectResults();
		}catch(Exception e){
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to get list of elements with error:"+e.getMessage(), e);
		}
	}

	public List<Map<String,Object>> getQueueElementsGroupByBundleId(String offset, String limit) throws DotPublisherException {
		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(ETENTRIESGROUPED);
			dc.setStartRow(offset);
			dc.setMaxRows(limit);

			return dc.loadObjectResults();
		}catch(Exception e){
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to get list of elements with error:"+e.getMessage(), e);
		}
	}

	private static final String ETBUNDLES="select distinct(bundle_id) as bundle_id, publish_date, operation from publishing_queue where publish_date is not null order by publish_date";

	private static final String COUNTBUNDLES="select count(distinct(bundle_id)) as bundle_count from publishing_queue where publish_date is not null";

	/**
	 * Gets the count of the bundles to be published
	 * @return
	 */
	public Integer countQueueBundleIds() throws DotPublisherException {
		DotConnect dc = new DotConnect();
		dc.setSQL(COUNTBUNDLES);
		try{
			Object total = dc.loadObjectResults().get(0).get("bundle_count");
			return Integer.parseInt(total.toString());
		}
		catch(Exception e){
			Logger.error(PublisherAPIImpl.class, e.getMessage());
			throw new DotPublisherException(e.getMessage());
		}
	}




	/**
	 * get bundle_ids available
	 * @return List<Map<String,Object>>
	 * @throws DotPublisherException
	 */
	public List<Map<String,Object>> getQueueBundleIds(int limit, int offest) throws DotPublisherException {
		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(ETBUNDLES);
			dc.setMaxRows(limit);
			dc.setStartRow(offest);
			return dc.loadObjectResults();
		}catch(Exception e){
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to get list of elements with error:"+e.getMessage(), e);
		}finally{
			DbConnectionFactory.closeConnection();
		}
	}

	private String SQLGETBUNDLESTOPROCESS =
			"select distinct(p.bundle_id) as bundle_id, " +
			"publish_date, operation, a.status "+
			"from publishing_queue p "+
			"left join publishing_queue_audit a "+
			"ON p.bundle_id=a.bundle_id "+
			"where "+
			"((a.status != ? and a.status != ?) or a.status is null ) and p.publish_date is not null "+
			"order by publish_date ASC,operation ASC";


	public List<Map<String, Object>> getQueueBundleIdsToProcess() throws DotPublisherException {
		try{
			DotConnect dc = new DotConnect();

			dc.setSQL(SQLGETBUNDLESTOPROCESS);

			dc.addParam(Status.BUNDLE_SENT_SUCCESSFULLY.getCode());
			dc.addParam(Status.PUBLISHING_BUNDLE.getCode());
			return dc.loadObjectResults();
		}catch(Exception e){
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to get list of elements with error:"+e.getMessage(), e);
		}finally{
			DbConnectionFactory.closeConnection();
		}
	}


	private static final String GETENTRIESBYBUNDLE=
			"SELECT * "+
			"FROM publishing_queue p where bundle_id = ? order by asset ";

	/**
	 * get queue elements by bundle_id
	 * @return List<Map<String,Object>>
	 * @throws DotPublisherException
	 */
	public List<PublishQueueElement> getQueueElementsByBundleId(String bundleId) throws DotPublisherException {
		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(GETENTRIESBYBUNDLE);
			dc.addParam(bundleId);

			return mapper.mapRows(dc.loadObjectResults());
		}catch(Exception e){
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to get list of elements with error:"+e.getMessage(), e);
		}finally{
			DbConnectionFactory.closeConnection();
		}
	}

	private static final String COUNTENTRIESGROUPED="select count(distinct(bundle_id)) as count from publishing_queue ";

	public Integer countQueueElementsGroupByBundleId() throws DotPublisherException {
		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(COUNTENTRIESGROUPED);
			return Integer.parseInt(dc.loadObjectResults().get(0).get("count").toString());
		}catch(Exception e){
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to get list of elements with error:"+e.getMessage(), e);
		}
	}

	private static final String GETENTRY="select * from publishing_queue where asset = ?";

	public List<PublishQueueElement> getQueueElementsByAsset(String asset) throws DotPublisherException {
		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(GETENTRY);

			dc.addParam(asset);

			return mapper.mapRows(dc.loadObjectResults());
		}catch(Exception e){
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to get list of elements with error:"+e.getMessage(), e);
		}
	}


	/**
	 * update element from publishing_queue table by id
	 */
	private static final String UPDATEELEMENTFROMQUEUESQL="UPDATE publishing_queue SET last_try=?, num_of_tries=?, in_error=?, last_results=? where id=?";
	/**
	 * update element from publishing_queue table by id
	 * @param id ID of the element in the publishing_queue
	 * @param next_try date of the next intent to execute the query
	 * @param in_error bolean indication if there was an error
	 * @param last_results error message
	 * @throws DotPublisherException
	 */
	public void updateElementStatusFromPublishQueueTable(long id, Date last_try,int num_of_tries, boolean in_error,String last_results ) throws DotPublisherException {

		boolean localTransaction = false;

		try {
			localTransaction = HibernateUtil.startLocalTransactionIfNeeded();
		} catch(DotDataException dde) {
			throw new DotPublisherException("Error starting Transaction", dde);
		}

		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(UPDATEELEMENTFROMQUEUESQL);
			dc.addParam(last_try);
			dc.addParam(num_of_tries);
			dc.addParam(in_error);
			dc.addParam(last_results);
			dc.addParam(id);
			dc.loadResult();

			if(localTransaction){
                HibernateUtil.commitTransaction();
            }
		}catch(Exception e){
		    if(localTransaction) {
    			try {
    				HibernateUtil.rollbackTransaction();
    			} catch (DotHibernateException e1) {
    				Logger.error(PublisherAPIImpl.class,e.getMessage(),e1);
    			}
		    }
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to update element "+id+" :"+e.getMessage(), e);
		}
	}

	/**
	 * Delete element from publishing_queue table by id
	 */
	private static final String DELETEELEMENTFROMQUEUESQL="DELETE FROM publishing_queue where asset=?";
	/**
	 * Delete element from publishing_queue table by bundleId
	 * @param id ID of the element in the table
	 * @return boolean
	 * @throws DotPublisherException
	 */
	public void deleteElementFromPublishQueueTable(String identifier) throws DotPublisherException{
		boolean localTransaction = false;

		try {
			localTransaction = HibernateUtil.startLocalTransactionIfNeeded();
		} catch(DotDataException dde) {
			throw new DotPublisherException("Error starting Transaction", dde);
		}

		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(DELETEELEMENTFROMQUEUESQL);
			dc.addParam(identifier);
			dc.loadResult();

			if(localTransaction) {
			    HibernateUtil.commitTransaction();
			}
		}catch(Exception e){
			if(localTransaction) {
			    try {
			        HibernateUtil.rollbackTransaction();
			    } catch (DotHibernateException e1) {
			        Logger.error(PublisherAPIImpl.class,e.getMessage(),e1);
			    }
			}
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to delete element "+identifier+" :"+e.getMessage(), e);
		}
	}

	/**
	 * Delete element(s) from publishing_queue table by id
	 */
	private static final String DELETEELEMENTSFROMQUEUESQL="DELETE FROM publishing_queue where bundle_id=?";
	/**
	 * Delete element from publishing_queue table by bundleId
	 * @param id ID of the element in the table
	 * @return boolean
	 * @throws DotPublisherException
	 */
	public void deleteElementsFromPublishQueueTable(String bundleId) throws DotPublisherException{
		boolean localTransaction = false;

		try {
			localTransaction = HibernateUtil.startLocalTransactionIfNeeded();
		} catch(DotDataException dde) {
			throw new DotPublisherException("Error starting Transaction", dde);
		}

		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(DELETEELEMENTSFROMQUEUESQL);
			dc.addParam(bundleId);
			dc.loadResult();

			if(localTransaction) {
			    HibernateUtil.commitTransaction();
			}
		}catch(Exception e){
		    if(localTransaction) {
    			try {
    				HibernateUtil.rollbackTransaction();
    			} catch (DotHibernateException e1) {
    				Logger.error(PublisherAPIImpl.class,e.getMessage(),e1);
    			}
		    }
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to delete element(s) "+bundleId+" :"+e.getMessage(), e);
		}
	}

	private static final String DELETEALLELEMENTFROMQUEUESQL="DELETE FROM publishing_queue";
	/**
	 * Delete all elements from publishing_queue table
	 * @return boolean
	 */
	public void deleteAllElementsFromPublishQueueTable() throws DotPublisherException{
		boolean localTransaction = false;

		try {
			localTransaction = HibernateUtil.startLocalTransactionIfNeeded();
		} catch(DotDataException dde) {
			throw new DotPublisherException("Error starting Transaction", dde);
		}

		try{
			DotConnect dc = new DotConnect();
			dc.setSQL(DELETEALLELEMENTFROMQUEUESQL);
			dc.loadResult();

			if(localTransaction) {
                HibernateUtil.commitTransaction();
            }
		}catch(Exception e){
		    if(localTransaction) {
    			try {
    				HibernateUtil.rollbackTransaction();
    			} catch (DotHibernateException e1) {
    				Logger.error(PublisherAPIImpl.class,e.getMessage(),e1);
    			}
		    }
			Logger.error(PublisherUtil.class,e.getMessage(),e);
			throw new DotPublisherException("Unable to delete elements :"+e.getMessage(), e);
		}
	}

	private static final String MULTI_TREE_CONTAINER_QUERY = new StringBuilder("select multi_tree.* from multi_tree ")
    .append("join htmlpage_version_info on htmlpage_version_info.identifier = multi_tree.parent1 ")
    .append("join container_version_info on container_version_info.identifier = multi_tree.parent2 ")
    .append("join contentlet_version_info on contentlet_version_info.identifier = multi_tree.child ")
    .append("where multi_tree.parent1 = ? ")
    .append("and (htmlpage_version_info.deleted = ? and container_version_info.deleted = ? ")
    .append("and contentlet_version_info.deleted = ?) ")
    .append("group by multi_tree.child, multi_tree.parent1, multi_tree.parent2, multi_tree.relation_type, multi_tree.tree_order")
    .append(" UNION ALL ")
    .append("select multi_tree.* from multi_tree ")
    .append("join htmlpage_version_info on htmlpage_version_info.identifier = multi_tree.parent1 ")
    .append("join container_version_info on container_version_info.identifier = multi_tree.parent2 ")
    .append("join contentlet_version_info on contentlet_version_info.identifier = multi_tree.child ")
    .append("where multi_tree.parent2 = ? ")
    .append("and (htmlpage_version_info.deleted = ? and container_version_info.deleted = ? ")
    .append("and contentlet_version_info.deleted = ?) ")
    .append("group by multi_tree.child, multi_tree.parent1, multi_tree.parent2, multi_tree.relation_type, multi_tree.tree_order")
    .append(" UNION ALL ")
    .append("select multi_tree.* from multi_tree ")
    .append("join htmlpage_version_info on htmlpage_version_info.identifier = multi_tree.parent1 ")
    .append("join container_version_info on container_version_info.identifier = multi_tree.parent2 ")
    .append("join contentlet_version_info on contentlet_version_info.identifier = multi_tree.child ")
    .append("where multi_tree.child = ? ")
    .append("and (htmlpage_version_info.deleted = ? and container_version_info.deleted = ? ")
    .append("and contentlet_version_info.deleted = ?) ")
    .append("group by multi_tree.child, multi_tree.parent1, multi_tree.parent2, multi_tree.relation_type, multi_tree.tree_order").toString();

	@Override
	public List<Map<String, Object>> getContainerMultiTreeMatrix(String id) throws DotPublisherException {
		List<Map<String,Object>> res;
		DotConnect dc=new DotConnect();
		dc.setSQL(MULTI_TREE_CONTAINER_QUERY);
		dc.addParam(id);
		dc.addParam(Boolean.FALSE);
		dc.addParam(Boolean.FALSE);
		dc.addParam(Boolean.FALSE);
		dc.addParam(id);
		dc.addParam(Boolean.FALSE);
		dc.addParam(Boolean.FALSE);
		dc.addParam(Boolean.FALSE);
		dc.addParam(id);
		dc.addParam(Boolean.FALSE);
		dc.addParam(Boolean.FALSE);
		dc.addParam(Boolean.FALSE);

		try {
			res = dc.loadObjectResults();
		} catch (Exception e) {
			Logger.error(PublisherAPIImpl.class,e.getMessage(),e);
			throw new DotPublisherException("Unable find multi tree:" + e.getMessage(), e);
		}

		return res;
	}
	@Override
	public void publishBundleAssets(String bundleId, Date publishDate)
			throws DotPublisherException {

		// update the already existing assets in the queue list with the publish operation and publish date

		DotConnect dc = new DotConnect();
        dc.setSQL( "UPDATE publishing_queue SET operation = ?, publish_date = ? where bundle_id = ?" );
        dc.addParam(ADD_OR_UPDATE_ELEMENT);
        dc.addParam(publishDate);
        dc.addParam(bundleId);

        try {
			dc.loadResult();
		} catch (DotDataException e) {
			Logger.error(getClass(), "Error updating bundles in publishing queue");
			throw new DotPublisherException("Error updating bundles in publishing queue", e);
		}

	}
	@Override
	public void unpublishBundleAssets(String bundleId, Date expireDate)
			throws DotPublisherException {

		// update the already existing assets in the queue list with the unpublish operation and expiration date

		DotConnect dc = new DotConnect();
        dc.setSQL( "UPDATE publishing_queue SET operation = ?, publish_date = ? where bundle_id = ?" );
        dc.addParam(DELETE_ELEMENT);
        dc.addParam(expireDate);
        dc.addParam(bundleId);

        try {
			dc.loadResult();
		} catch (DotDataException e) {
			Logger.error(getClass(), "Error updating bundles in publishing queue");
			throw new DotPublisherException("Error updating bundles in publishing queue", e);
		}

	}
	@Override
	public void publishAndExpireBundleAssets(String bundleId, Date publishDate,
			Date expireDate, User user) throws DotPublisherException {

		// update the already existing assets in the queue list with the publish operation and publish date

		publishBundleAssets(bundleId, publishDate);

        // insert a new version of each asset but with the unpublish operation, the expiration date AND a NEW BUNDLE ID

		List<PublishQueueElement> assets = getQueueElementsByBundleId(bundleId);

		List<String> ids = new ArrayList<String>();

		for (PublishQueueElement asset : assets) {
			ids.add(asset.getAsset());
		}

		try {

			Bundle publishBundle = APILocator.getBundleAPI().getBundleById(bundleId);
			List<Environment> envsToSendTo = APILocator.getEnvironmentAPI().findEnvironmentsByBundleId(bundleId);

			Bundle deleteBundle = new Bundle(publishBundle.getName(), publishDate, expireDate, user.getUserId(), publishBundle.isForcePush());
	    	APILocator.getBundleAPI().saveBundle(deleteBundle, envsToSendTo);

	        addContentsToUnpublish( ids, deleteBundle.getId(), expireDate, user );

		} catch (DotDataException e) {
			throw new DotPublisherException(e);
		}

	}

}
