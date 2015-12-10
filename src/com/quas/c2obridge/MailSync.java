package com.quas.c2obridge;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IdleManager;

import javax.mail.Folder;
import javax.mail.Session;
import javax.mail.Store;
import java.util.concurrent.ExecutorService;

/**
 * Contains the most-recently updated inbox.
 *
 * Created by Quasar on 10/12/2015.
 */
public class MailSync {

	private static Store store;
	private static IMAPFolder inbox;
	private static IdleManager idle;
	private static Session session;
	private static ExecutorService executorService;

	private static int setStoreCount = 0;

	public synchronized static void shutdownExecutorService() {
		executorService.shutdown();
		executorService = null;
	}

	public synchronized static void setExecutorService(ExecutorService es) {
		executorService = es;
	}

	public synchronized static void setSession(Session newSession) {
		session = newSession;
	}

	public synchronized static Session getSession() {
		return session;
	}

	public synchronized static Store getStore() {
		return store;
	}

	public synchronized static void setStore(Store newStore) {
		store = newStore;
		setStoreCount++;
		if (setStoreCount > 1) throw new RuntimeException("setStore() called more than once");
	}

	public synchronized static IMAPFolder getInbox() {
		return inbox;
	}

	public synchronized static void setInbox(Folder newInbox) {
		inbox = (IMAPFolder) newInbox;
	}

	public synchronized static void setIdleManager(IdleManager newIdle) {
		idle = newIdle;
	}

	public synchronized static IdleManager getIdleManager() {
		return idle;
	}
}
