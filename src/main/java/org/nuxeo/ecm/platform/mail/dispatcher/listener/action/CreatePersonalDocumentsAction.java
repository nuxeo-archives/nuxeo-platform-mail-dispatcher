package org.nuxeo.ecm.platform.mail.dispatcher.listener.action;

import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.ATTACHMENTS_KEY;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.CC_RECIPIENTS_KEY;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.CC_RECIPIENTS_PROPERTY_NAME;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.MAIL_MESSAGE_TYPE;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.RECIPIENTS_KEY;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.RECIPIENTS_PROPERTY_NAME;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.SENDER_KEY;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.SENDER_EMAIL_KEY;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.SENDER_PROPERTY_NAME;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.SENDING_DATE_KEY;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.SENDING_DATE_PROPERTY_NAME;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.SUBJECT_KEY;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.TEXT_KEY;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.TEXT_PROPERTY_NAME;

import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.Base64;
import org.nuxeo.common.utils.IdUtils;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.security.impl.ACLImpl;
import org.nuxeo.ecm.core.api.security.impl.ACPImpl;
import org.nuxeo.ecm.directory.DirectoryException;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.ecm.platform.mail.action.ExecutionContext;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.ecm.platform.userworkspace.api.UserWorkspaceService;
import org.nuxeo.ecm.platform.mail.listener.action.AbstractMailAction;
import org.nuxeo.runtime.api.Framework;

/**
 * Creates a MailMessage document for every new email found in the INBOX. The
 * documents are created into the users' personal workspaces.
 *
 * The properties values are retrieved from the pipe execution context.
 *
 * @author <a href="mailto:cbaican@nuxeo.com">Catalin Baican</a>
 *
 */
public class CreatePersonalDocumentsAction extends AbstractMailAction {

    private static final Log log = LogFactory.getLog(CreatePersonalDocumentsAction.class);

    public static final String ID_SEP = ":";

    public static final String EMAILS_FOLDER_TITLE = "Emails";

    public static final String EMAILS_LIST_TITLE = "EMAILS";

    private UserWorkspaceService userWorkspaceService;

    private UserManager userManager;

    private transient DirectoryService directoryService;

    @SuppressWarnings("unchecked")
    public boolean execute(ExecutionContext context) throws Exception {
        CoreSession session = getCoreSession(context);
        if (session == null) {
            log.error("Could not open CoreSession");
            return false;
        }

        String subject = (String) context.get(SUBJECT_KEY);
        String sender = (String) context.get(SENDER_KEY);
        String senderEmailAddress = (String) context.get(SENDER_EMAIL_KEY);

        Date sendingDate = (Date) context.get(SENDING_DATE_KEY);
        ArrayList<String> recipients = (ArrayList<String>) context.get(RECIPIENTS_KEY);
        ArrayList<String> ccRecipients = (ArrayList<String>) context.get(CC_RECIPIENTS_KEY);
        List<FileBlob> attachments = (List<FileBlob>) context.get(ATTACHMENTS_KEY);
        String text = (String) context.get(TEXT_KEY);

        userManager = getUserManager();
        Map<String, Serializable> filter = new HashMap<String, Serializable>();
        filter.put("email", senderEmailAddress);
        DocumentModelList users = userManager.searchUsers(filter, null);

        if (users != null && !users.isEmpty()) {
            for (DocumentModel currentUser : users) {
                String currentUserName = currentUser.getId();

                DocumentModel personalMailFolder = getOrCreatePersonalMailFolder(
                        currentUserName, session);

                DocumentModel documentModel = session.createDocumentModel(
                        personalMailFolder.getPathAsString(),
                        IdUtils.generateId(subject + System.currentTimeMillis()),
                        MAIL_MESSAGE_TYPE);
                documentModel.setPropertyValue("dc:title", subject);
                documentModel.setPropertyValue(SENDER_PROPERTY_NAME, sender);
                documentModel.setPropertyValue(SENDING_DATE_PROPERTY_NAME,
                        sendingDate);
                documentModel.setPropertyValue(RECIPIENTS_PROPERTY_NAME,
                        recipients);
                documentModel.setPropertyValue(CC_RECIPIENTS_PROPERTY_NAME,
                        ccRecipients);
                if (attachments != null && !attachments.isEmpty()) {
                    ArrayList<Map<String, Serializable>> files = new ArrayList<Map<String, Serializable>>();
                    for (FileBlob currentFileBlob : attachments) {
                        if (currentFileBlob != null) {
                            Map<String, Serializable> file = new HashMap<String, Serializable>();
                            file.put("file", currentFileBlob);
                            file.put("filename", currentFileBlob.getFilename());
                            files.add(file);
                        }
                    }
                    documentModel.setPropertyValue("files:files", files);
                }
                documentModel.setPropertyValue(CC_RECIPIENTS_PROPERTY_NAME,
                        ccRecipients);
                documentModel.setPropertyValue(TEXT_PROPERTY_NAME, text);

                documentModel = saveDocumentToPersonalWorkspace(session, documentModel,
                        currentUserName);

                addDocumentToPersistentList(currentUserName, EMAILS_LIST_TITLE,
                        documentModel);
            }
        }

        return true;
    }

    public void reset(ExecutionContext context) throws Exception {
        // do nothing
    }

    private DocumentModel getOrCreatePersonalMailFolder(String currentUserName,
            CoreSession coreSession) throws ClientException {
        userWorkspaceService = getUserWorkspaceService();
        // Get WorkspaceRoot as context document model reference;
        DocumentModel contextDocumentModel = coreSession.query(
                "SELECT * FROM WorkspaceRoot").get(0);
        DocumentModel currentUserPersonalWorkspace = userWorkspaceService.getCurrentUserPersonalWorkspace(
                currentUserName, contextDocumentModel);

        DocumentModelList childrenDocuments = coreSession.getChildren(
                currentUserPersonalWorkspace.getRef(), "Folder");
        if (childrenDocuments != null && !childrenDocuments.isEmpty()) {
            for (DocumentModel currentChildDocument : childrenDocuments) {
                if (!"deleted".equals(currentChildDocument.getCurrentLifeCycleState())
                        && EMAILS_FOLDER_TITLE.equals(currentChildDocument.getTitle())) {
                    return currentChildDocument;
                }
            }
        }

        DocumentModel personalMailFolder = coreSession.createDocumentModel(
                currentUserPersonalWorkspace.getPathAsString(),
                IdUtils.generateId(EMAILS_FOLDER_TITLE
                        + System.currentTimeMillis()), "Folder");
        personalMailFolder.setPropertyValue("dc:title", EMAILS_FOLDER_TITLE);

        saveDocumentToPersonalWorkspace(coreSession, personalMailFolder,
                currentUserName);

        return personalMailFolder;
    }

    private DocumentModel saveDocumentToPersonalWorkspace(CoreSession session,
            DocumentModel documentModel, String userName)
            throws ClientException {
        documentModel = session.createDocument(documentModel);

        ACP acp = new ACPImpl();
        ACE grantEverything = new ACE(userName, SecurityConstants.EVERYTHING,
                true);
        ACL acl = new ACLImpl();
        acl.setACEs(new ACE[] { grantEverything });
        acp.addACL(acl);
        documentModel.setACP(acp, true);

        session.save();
        return documentModel;
    }

    private boolean addDocumentToPersistentList(String userName,
            String listName, DocumentModel doc) {

        directoryService = getDirectoryService();

        String ref = doc.getRef().toString();
        int refType = doc.getRef().type();
        String repoId = doc.getRepositoryName();

        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("listid", listName);
        fields.put("userid", userName);
        fields.put("ref", ref);
        fields.put("reftype", (long) refType);
        fields.put("repo", repoId);
        String id = getIdForEntry(userName, listName, ref, doc.getRef().type(),
                repoId);
        fields.put("id", id);

        Session dirSession = null;
        try {
            dirSession = openSession();
            dirSession.createEntry(fields);
        } catch (Exception e) {
            log.error("Unable to create entry : " + e.getMessage());
            closeSession(dirSession);
            return false;
        }

        closeSession(dirSession);

        return true;
    }

    private Session openSession() throws DirectoryException {
        return getDirectoryService().open("documentsLists");
    }

    private void closeSession(Session session) {
        try {
            session.close();
        } catch (DirectoryException e) {
            // ignore
        }
    }

    private UserManager getUserManager() {
        if (userManager != null) {
            return userManager;
        }
        try {
            userManager = Framework.getService(UserManager.class);
        } catch (Exception e) {
            log.error("Exception getting userworkspace service");
        }
        return userManager;
    }

    private UserWorkspaceService getUserWorkspaceService() {
        if (userWorkspaceService != null) {
            return userWorkspaceService;
        }
        try {
            userWorkspaceService = Framework.getService(UserWorkspaceService.class);
        } catch (Exception e) {
            log.error("Exception getting userworkspace service");
        }
        return userWorkspaceService;
    }

    private DirectoryService getDirectoryService() {
        if (directoryService != null) {
            return directoryService;
        }
        try {
            directoryService = Framework.getService(DirectoryService.class);
        } catch (Exception e) {
            log.error("Exception getting directory service");
        }
        return directoryService;
    }

    private String getIdForEntry(String userName, String listName, String ref,
            int refType, String repoId) {
        StringBuilder sb = new StringBuilder();
        sb.append(listName);
        sb.append(ID_SEP);
        sb.append(userName);
        sb.append(ID_SEP);
        sb.append(refType);
        sb.append(ID_SEP);
        sb.append(ref);
        sb.append(ID_SEP);
        sb.append(repoId);

        byte[] idDigest;
        try {
            idDigest = MessageDigest.getInstance("MD5").digest(
                    sb.toString().getBytes());
        } catch (NoSuchAlgorithmException e) {
            // should never append
            return sb.toString();
        }
        return Base64.encodeBytes(idDigest);
    }
}
