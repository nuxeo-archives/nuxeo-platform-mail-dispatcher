package org.nuxeo.ecm.platform.mail.dispatcher.listener;

import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.CORE_SESSION_ID_KEY;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.IMAP;
import static org.nuxeo.ecm.platform.mail.utils.MailCoreConstants.MIMETYPE_SERVICE_KEY;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Flags.Flag;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.platform.mail.action.ExecutionContext;
import org.nuxeo.ecm.platform.mail.action.MessageActionPipe;
import org.nuxeo.ecm.platform.mail.action.Visitor;
import org.nuxeo.ecm.platform.mail.service.MailService;
import org.nuxeo.ecm.platform.mimetype.interfaces.MimetypeRegistry;
import org.nuxeo.runtime.api.Framework;

/**
 * Listens for PersonalMailReceivedEvent. The email connection corresponding to
 * every MailFolder document found in the repository is checked for new incoming
 * email.
 *
 * @author <a href="mailto:cbaican@nuxeo.com">Catalin Baican</a>
 *
 */
public class MailDispatcherEventListener implements EventListener {

    public static final String EVENT_NAME = "MailDispatcherEvent";

    public static final String PIPE_NAME = "mailDispatcher";

    public static final String INBOX = "INBOX";

    public static final String DELETED_LIFECYCLE_STATE = "deleted";

    public static final long EMAILS_LIMIT_DEFAULT = 100;

    private static final Log log = LogFactory.getLog(MailDispatcherEventListener.class);

    private MailService mailService;

    private MimetypeRegistry mimeService;

    public void handleEvent(Event event) {
        String eventId = event.getName();

        if (!EVENT_NAME.equals(eventId)) {
            return;
        }

        String email = Framework.getProperty("maildispatcher.email");
        String password = Framework.getProperty("maildispatcher.password");
        if (!StringUtils.isEmpty(email) && !StringUtils.isEmpty(password)) {
            mailService = getMailService();
            LoginContext loginContext = null;
            CoreSession coreSession = null;
            Folder rootFolder = null;

            try {
                // open a system session
                loginContext = Framework.login();
                RepositoryManager mgr = Framework.getService(RepositoryManager.class);
                coreSession = mgr.getDefaultRepository().open();

                MessageActionPipe pipe = mailService.getPipe(PIPE_NAME);

                Visitor visitor = new Visitor(pipe);
                Thread.currentThread().setContextClassLoader(
                        Framework.class.getClassLoader());

                // initialize context
                ExecutionContext initialExecutionContext = new ExecutionContext();

                initialExecutionContext.put(MIMETYPE_SERVICE_KEY,
                        getMimeService());

                initialExecutionContext.put(CORE_SESSION_ID_KEY,
                        coreSession.getSessionId());

                String protocolType = Framework.getProperty("maildispatcher.protocol.type");
                String host = Framework.getProperty("maildispatcher.host");
                String port = Framework.getProperty("maildispatcher.port");
                Boolean socketFactoryFallback = Boolean.valueOf(Framework.getProperty("maildispatcher.socket.factory.fallback"));
                String socketFactoryPort = Framework.getProperty("maildispatcher.socket.factory.port");
                Boolean starttlsEnable = Boolean.valueOf(Framework.getProperty("maildispatcher.starttls.enable"));
                String sslProtocols = Framework.getProperty("maildispatcher.ssl.protocols");
                Long emailsLimit = Long.valueOf(Framework.getProperty("maildispatcher.emails.limit"));
                long emailsLimitLongValue = emailsLimit == null ? EMAILS_LIMIT_DEFAULT
                        : emailsLimit.longValue();

                Properties properties = new Properties();
                properties.put("mail.store.protocol", protocolType);
                // Is IMAP connection
                if (IMAP.equals(protocolType)) {
                    properties.put("mail.imap.host", host);
                    properties.put("mail.imap.port", port);
                    properties.put("mail.imap.socketFactory.class",
                            "javax.net.ssl.SSLSocketFactory");
                    properties.put("mail.imap.socketFactory.fallback",
                            socketFactoryFallback.toString());
                    properties.put("mail.imap.socketFactory.port",
                            socketFactoryPort);
                    properties.put("mail.imap.starttls.enable",
                            starttlsEnable.toString());
                    properties.put("mail.imap.ssl.protocols", sslProtocols);
                } else {
                    // Is POP3 connection
                    properties.put("mail.pop3.host", host);
                    properties.put("mail.pop3.port", port);
                    properties.put("mail.pop3.socketFactory.class",
                            "javax.net.ssl.SSLSocketFactory");
                    properties.put("mail.pop3.socketFactory.fallback",
                            socketFactoryFallback.toString());
                    properties.put("mail.pop3.socketFactory.port",
                            socketFactoryPort);
                }

                Session session = Session.getInstance(properties);
                Store store = session.getStore();
                store.connect(email, password);

                rootFolder = store.getFolder(INBOX);
                rootFolder.open(Folder.READ_WRITE);

                Message[] allMessages = rootFolder.getMessages();
                FetchProfile fetchProfile = new FetchProfile();
                fetchProfile.add(FetchProfile.Item.FLAGS);
                rootFolder.fetch(allMessages, fetchProfile);

                List<Message> unreadMessagesList = new ArrayList<Message>();
                for (int i = 0; i < allMessages.length; i++) {
                    Flags flags = allMessages[i].getFlags();
                    int unreadMessagesListSize = unreadMessagesList.size();
                    if (flags != null && !flags.contains(Flag.SEEN)
                            && unreadMessagesListSize < emailsLimitLongValue) {
                        unreadMessagesList.add(allMessages[i]);
                        if (unreadMessagesListSize == emailsLimitLongValue - 1) {
                            break;
                        }
                    }
                }

                // perform email import
                visitor.visit(
                        unreadMessagesList.toArray(new Message[unreadMessagesList.size()]),
                        initialExecutionContext);
            } catch (Exception e) {
                log.error("MailDispatcherEventListener error...", e);
            } finally {
                if (rootFolder != null && rootFolder.isOpen()) {
                    try {
                        rootFolder.close(true);
                    } catch (MessagingException e) {
                        log.error("MailDispatcherEventListener error...", e);
                    }
                }
                if (loginContext != null) {
                    try {
                        loginContext.logout();
                    } catch (LoginException e) {
                    }
                }
                if (coreSession != null) {
                    CoreInstance.getInstance().close(coreSession);
                }
            }
        }
    }

    private MailService getMailService() {
        if (mailService == null) {
            try {
                mailService = Framework.getService(MailService.class);
            } catch (Exception e) {
                log.error("Exception getting mail service");
            }
        }

        return mailService;
    }

    private MimetypeRegistry getMimeService() {
        if (mimeService == null) {
            try {
                mimeService = Framework.getService(MimetypeRegistry.class);
            } catch (Exception e) {
                log.error("Exception getting mime service");
            }
        }

        return mimeService;
    }
}
