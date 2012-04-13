/* -*- Mode: C++; tab-width: 4; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * The contents of this file are subject to the Netscape Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/NPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * The Original Code is mozilla.org code.
 *
 * The Initial Developer of the Original Code is Netscape
 * Communications Corporation.  Portions created by Netscape are
 * Copyright (C) 1999 Netscape Communications Corporation. All
 * Rights Reserved.
 *
 * Contributor(s): 
 */
package com.sun.identity.shared.ldap;

import com.sun.identity.common.GeneralTaskRunnable;
import com.sun.identity.common.SystemTimerPool;
import com.sun.identity.common.TimerPool;
import com.sun.identity.shared.ldap.*;
import java.util.*;
import java.io.*;
import com.sun.identity.shared.ldap.client.*;
import com.sun.identity.shared.ldap.util.*;
import java.util.zip.CRC32;

/**
 * <CODE>LDAPCache</CODE> represents an in-memory cache that you can use 
 * to reduce the number of search requests sent to the LDAP server.
 * <P>
 *
 * Each item in the cache represents a search request and
 * its results.  Each item is uniquely identified by the
 * search criteria, which includes:
 * <P>
 *
 * <UL>
 * <LI>the host name and port number of the LDAP server
 * <LI>the base DN of the search
 * <LI>the search filter
 * <LI>the scope of the search
 * <LI>the attributes to be returned in the search results
 * <LI>the DN used to authenticate the client when binding
 *     to the server
 * <LI>the LDAP v3 controls specified in the search request
 * </UL>
 * <P>
 *
 * After a search request is cached, the results of any
 * subsequent search requests using the same criteria are
 * read from the cache.  Note that if any part of the
 * criteria differs (for example, if a different DN is used
 * when binding to the server or if a different set of
 * attributes to be returned is specified), the search
 * request is sent to the server.
 * <P>
 *
 * When you create the cache, you specify the maximum amount
 * of time that an item can be kept in the cache.  When an
 * item's age exceeds that time limit, the item is removed
 * from the cache.
 * <P>
 *
 * The cache also has a maximum size that you specify when
 * creating the cache.  If adding a new item exceeds the
 * maximum size of the cache, the first entries in the cache
 * are removed to make enough space for the new item.
 * <P>
 *
 * Finally, when creating the cache, you can specify a list
 * of the base DNs in search requests that you want to cache.
 * For example, if you specify <CODE>o=Example.com</CODE> as
 * a base DN to cache, your client caches search requests
 * where the base DN is <CODE>o=Example.com</CODE>.
 * <P>
 *
 * To specify that you want to use a cache for a particular
 * LDAP session, call the <CODE>setCache</CODE> method of
 * the <CODE>LDAPConnection</CODE> object that you are
 * working with.
 * <P>
 *
 * All clones of an <CODE>LDAPConnection</CODE> object share
 * the same <CODE>LDAPCache</CODE> object.
 * <P>
 * 
 * Note that <CODE>LDAPCache</CODE> does not maintain consistency 
 * with the directory, so that cached search results may no longer be
 * valid after a directory update. If the same application is performing 
 * both cached searches and directory updates, then the 
 * application should flush the corresponding cache entries after an update.
 * To do this use the <CODE>flushEntries</CODE> method.
 * <P>
 *
 * Also, note that search requests that return referrals are not cached.
 * <P>
 *
 * The <CODE>LDAPCache</CODE> class includes methods for
 * getting statistics (such as hit rates) from the cache and
 * for flushing entries from the cache.
 * <P>
 *
 * @see com.sun.identity.shared.ldap.LDAPConnection#setCache(com.sun.identity.shared.ldap.LDAPCache)
 * @see com.sun.identity.shared.ldap.LDAPConnection#getCache
 * @deprecated As of ForgeRock OpenAM 10.
 */
public class LDAPCache implements Serializable {
    static final long serialVersionUID = 6275167993337814294L;
    
    /**
     * A hashtable of search results. The key is created from the search
     * request parameters (see createKey() method). The value is a Vector
     * where the first element is a Long integer representing the size
     * of all entries, followed by the actual search result entries (of type
     * LDAPEntry).
     */    
    private Hashtable m_cache;
    
    /**
     * A list of cached entries ordered by time (augments m_cache). Each
     * element in the list is a 2 element Vector where the element at index
     * 0 is the key in the m_cache table, and the element at index 1 is the
     * time when the entry was created.
     * The list is used to track the time-to-live limit and to implement the
     * FIFO algorithm when adding new entries; if the size of the new entry
     * exceeds the cache available space, the extra space is made by removing
     * existing cached results in the order of their entry in the cache.
     */    
    protected OrderedNode headNode;
    protected OrderedNode tailNode;    
    private long m_timeToLive;
    private long m_maxSize;
    private String[] m_dns;
    private long m_remainingSize = 0;

    // Count of LDAPConnections that share this cache
    private int m_refCnt = 0;

    /**
     * Delimiter used internally when creating keys
     * for the cache.
     */
    public static final String DELIM = "#";    
    private TTLTask m_cleanUpTask = null;
    private static transient TimerPool timerPool =
        SystemTimerPool.getTimerPool();    
    private long m_totalOpers = 0;
    private long m_hits = 0;
    private long m_flushes = 0;

    // Debug can be activated by defining debug.cache property    
    private static boolean m_debug = false;
    static {
        try {
            String traceProp = System.getProperty("debug.cache");
            m_debug = (traceProp != null);
        }
        catch (Exception e) {
            ;// In browser access to property might not be allowed
        }
    }
    
    /**
     * Constructs a new <CODE>LDAPCache</CODE> object, using the
     * specified maximum size of the cache (in bytes) and the maximum
     * age of cached items (in seconds).  When items in the cache
     * exceed this age, they are removed from the cache.
     * <P>
     *
     * @param ttl the maximum amount of time that an item can be cached
     *  (in seconds)
     * @param size the maximum size of the cache (in bytes)
     */
    public LDAPCache(long ttl, long size)
    {
        init(ttl, size);
    }

    /**
     * Constructs a new <CODE>LDAPCache</CODE> object, using the
     * specified maximum size of the cache (in bytes), and the maximum
     * age of cached items (in seconds), and an array of  the base DNs
     * of searches that you want to cache.  (For example,
     * if the array of base DNs includes <CODE>o=Example.com</CODE>,
     * the cache stores search results if the base DN in the search
     * request is <CODE>o=Example.com</CODE>.)
     * <P>
     *
     * @param ttl the maximum amount of time that an item can be cached
     *  (in seconds)
     * @param size the maximum size of the cache (in bytes)
     * @param dns the list of base DNs of searches that you want to cache.
     */
    public LDAPCache(long ttl, long size, String[] dns)
    {
        init(ttl, size);

        m_dns = new String[dns.length];
        if ((dns != null) && (dns.length > 0))
            for (int i=0; i<dns.length; i++) {
                m_dns[i] = (new DN(dns[i])).toString();
        }
    }

    /**
     * Gets the maximum size of the cache (in bytes).
     * <P>
     *
     * @return the maximum size of the cache (in bytes).
     */
    public long getSize()
    {
        return m_maxSize;
    }

    /**
     * Gets the maximum age allowed for cached items (in
     * seconds).  (Items that exceed this age are
     * removed from the cache.)
     * <P>
     *
     * @return the maximum age of items in the cache (in
     *  seconds).
     */
    public long getTimeToLive()
    {
        return m_timeToLive/1000;
    }

    /**
     * Gets the array of base DNs of searches to be cached.
     * (Search requests with these base DNs are cached.)
     * <P>
     *
     * @return the array of base DNs.
     */
    public String[] getBaseDNs()
    {
        return m_dns;
    }

    /**
     * Flush the entries identified by DN and scope from the cache.
     * <P>
     *
     * @param dn the distinguished name (or base DN) of the entries
     *  to be removed from the cache. Use this parameter in conjunction
     *  with <CODE>scope</CODE> to identify the entries that you want
     *  removed from the cache.  If this parameter is <CODE>null</CODE>,
     *  the entire cache is flushed.
     * @param scope the scope identifying the entries that you want
     *  removed from the cache. The value of this parameter can be
     *  one of the following:
     *  <UL>
     *  <LI><CODE>LDAPv2.SCOPE_BASE</CODE> (to remove the entry identified
     *      by <CODE>dn</CODE>)
     *  <LI><CODE>LDAPv2.SCOPE_ONE</CODE> (to remove the entries that
     *      have <CODE>dn</CODE> as their parent entry)
     *  <LI><CODE>LDAPv2.SCOPE_SUB</CODE> (to remove the entries in the
     *      subtree under <CODE>dn</CODE> in the directory)
     *  </UL>
     * <P>
     * @return <CODE>true</CODE> if the entry is removed from the cache;
     * <CODE>false</CODE> if the entry is not removed.
     */
    public synchronized boolean flushEntries(String dn, int scope) {

        if (m_debug)
            System.out.println("DEBUG: User request for flushing entry: dn "+
            dn+" and scope "+scope);
        // if the dn is null, invalidate the whole cache
        if (dn == null)
        {
            // reclaim all the cache spaces
            m_remainingSize = m_maxSize;
            m_cache.clear();            
            headNode = null;
            tailNode = null;            
            // reset stats
            m_totalOpers = m_hits = m_flushes = 0;

            return true;
        }

        DN dn2 = new DN(dn);
        OrderedNode nextNode = headNode;        
        while (nextNode != null) {
            Vector val = nextNode.getValue();
            // LDAPEntries start at idx 1, at idx 0 is a Long
            // (size of all LDAPEntries returned by search())
            int j=1;
            int size2=val.size();

            for (; j < size2; j++) {
                String d = ((LDAPEntry)val.elementAt(j)).getDN();
                DN dn1 = new DN(d);

                if (dn1.equals(dn2)) {                    
                    break;
                }
                if (scope == LDAPConnection.SCOPE_ONE) {
                    DN parentDN1 = dn1.getParent();
                    if (parentDN1.equals(dn2)) {
                        break;
                    }
                }
                if ((scope == LDAPConnection.SCOPE_SUB) &&
                    (dn1.isDescendantOf(dn2))) {
                    break;
                }
            }
            if (j < size2) {
                if (nextNode.getPrev() == null) {
                    headNode = nextNode.getNext();
                    if (headNode != null) {
                        headNode.setPrev(null);
                    }
                } else {
                    nextNode.getPrev().setNext(nextNode.getNext());
                }                
                if (nextNode.getNext() == null) {
                    tailNode = nextNode.getPrev();                                        
                    if (tailNode != null) {
                        tailNode.setNext(null);
                    }
                } else {
                    nextNode.getNext().setPrev(nextNode.getPrev());
                }                
                Vector entry = ((OrderedNode)m_cache.remove(
                    nextNode.getKey())).getValue();
                m_remainingSize += ((Long)entry.firstElement()).longValue();
                if (m_debug)
                    System.out.println("DEBUG: Successfully removed entry ->" +
                        nextNode.getKey());

                return true;
            }
            
            nextNode = nextNode.getNext();
        }        
        if (m_debug)
            System.out.println("DEBUG: The number of keys in the cache is "
            +m_cache.size());

        return false;
    }

    /**
     * Gets the amount of available space (in bytes) left in the cache.
     * <P>
     *
     * @return the available space (in bytes) in the cache.
     */
    public long getAvailableSize() {
        return m_remainingSize;
    }

    /**
     * Gets the total number of requests for retrieving items from
     * the cache.  This includes both items successfully found in
     * the cache and items not found in the cache.
     * <P>
     *
     * @return the total number of requests for retrieving items from
     * the cache.
     */
    public long getTotalOperations() {
        return m_totalOpers;
    }

    /**
     * Gets the total number of requests which failed to find and
     * retrieve an item from the cache.
     * <P>
     *
     * @return the number of requests that did not find and retrieve
     *  an item in the cache.
     */
    public long getNumMisses() {
        return (m_totalOpers - m_hits);
    }

    /**
     * Gets the total number of requests which successfully found and
     * retrieved an item from the cache.
     * @return the number of requests that successfully found and
     *  retrieved an item from the cache.
     */
    public long getNumHits() {
        return m_hits;
    }

    /**
     * Gets the total number of entries that are flushed when timer expires
     *  and <CODE>flushEntries</CODE> is called.
     * <P>
     *
     * @return the total number of entries that are flushed when timer
     *  expires.
     */
    public long getNumFlushes() {
        return m_flushes;
    }

    /**
     * Create a key for a cache entry by concatenating all input parameters
     * @return the key for a cache entry
     * @exception LDAPException Thrown when failed to create key.
     */
    Long createKey(String host, int port, String baseDN, String filter,
      int scope, String[] attrs, String bindDN, LDAPConstraints cons)
      throws LDAPException {

        DN dn = new DN(baseDN);
        baseDN = dn.toString();

        if (m_dns != null) {
            int i=0;
            for (; i<m_dns.length; i++) {
                if (baseDN.equals(m_dns[i]))
                    break;
            }

            if (i >= m_dns.length)
                throw new LDAPException(baseDN+" is not a cached base DN",
                LDAPException.OTHER);
        }
        
        StringBuffer key = new StringBuffer();
        appendString(baseDN, key);
        appendString(scope, key);
        appendString(host, key);
        appendString(port, key);
        appendString(filter, key);
        appendString(attrs, key);
        appendString(bindDN, key);

        LDAPControl[] serverControls = null;
        LDAPControl[] clientControls = null;

        // get server and client controls
        if (cons != null)
        {
            serverControls = cons.getServerControls();
            clientControls = cons.getClientControls();
        }

        if ((serverControls != null) && (serverControls.length > 0))
        {
            StringBuffer[] objIDBuf = new StringBuffer[serverControls.length];
            String[] objID = new String[serverControls.length];
            for (int i=0; i<serverControls.length; i++) {
                LDAPControl ctrl = serverControls[i];
                long val = getCRC32(ctrl.getValue());                
                objIDBuf[i] = new StringBuffer();
                objIDBuf[i].append(ctrl.getID());
                objIDBuf[i].append(ctrl.isCritical());
                objIDBuf[i].append(val); 
                objID[i] = objIDBuf[i].toString();               
            }
            appendString(objID, key);
        }
        else {            
            appendString(0, key);
        }

        if ((clientControls != null) && (clientControls.length > 0))
        {
            StringBuffer[] objIDBuf = new StringBuffer[clientControls.length];
            String[] objID = new String[clientControls.length];
            for (int i=0; i<clientControls.length; i++) {
                LDAPControl ctrl = clientControls[i];
                long val = getCRC32(ctrl.getValue());
                objIDBuf[i] = new StringBuffer();
                objIDBuf[i].append(ctrl.getID());
                objIDBuf[i].append(ctrl.isCritical());
                objIDBuf[i].append(val);
                objID[i] = objIDBuf[i].toString();                
            }
            appendString(objID, key);
        }
        else {
            appendString(0, key);
        }
        long val = getCRC32(key.toString().getBytes());        
        if(m_debug) {
            System.out.println("key="+val + " for "+key);
        }
        return new Long(val);
    }

    /**
     * Gets the cache entry based on the specified key.
     * @param key the key for the cache entry
     * @return the cache entry.
     */
    synchronized Object getEntry(Long key) {
        Object obj = null;

        OrderedNode entry = (OrderedNode) m_cache.get(key);
        if (entry != null) {
            obj =  entry.getValue();
        }        
        m_totalOpers++;

        if (m_debug) {
            if (obj == null)
                System.out.println("DEBUG: Entry whose key -> "+key+
                    " not found in the cache.");
            else
                System.out.println("DEBUG: Entry whose key -> "+key+
                    " found in the cache.");
        }

        if (obj != null)
            m_hits++;


        return obj;
    }

    /**
     * Flush entries which stay longer or equal to the time-to-live.
     */    
    synchronized long flushEntries()
    {
        Vector v = null;
        boolean delete = false;

        long currTime = System.currentTimeMillis();

        m_flushes = 0;
            if (headNode == null) {
                return -1;
            }
            OrderedNode nextNode = headNode;
            while ((nextNode != null) && (nextNode.getTimeout() <= currTime)) {                
                if (m_debug) {
                    System.out.println("DEBUG: Timer flush entry whose key is "+
                        nextNode.getKey());
                }
                m_cache.remove(nextNode.getKey());
                m_remainingSize += ((Long)
                    nextNode.getValue().firstElement()).longValue();
                m_flushes++;
                nextNode = nextNode.getNext();
            }            

        if (m_debug)
            System.out.println("DEBUG: The number of keys in the cache is "
                +m_cache.size());
        headNode = nextNode;
        if (headNode != null) {
            headNode.setPrev(null);
            return headNode.getTimeout(); 
        } else {
            tailNode = null;
        }        
        return -1;
    }

    /**
     * Add the entry to the hashtable cache and to the vector respectively.
     * The vector is used to keep track of the order of the entries being added.
     * @param key the key for the cache entry
     * @param value the cache entry being added to the cache for the specified
     * key
     * @return a flag indicating whether the entry was added.
     */
    synchronized boolean addEntry(Long key, Object value)
    {
        // if entry exists, dont perform add operation
        if (m_cache.get(key) != null)
            return false;

        Vector v = (Vector)value;
        long size = ((Long)v.elementAt(0)).longValue();

        if (size > m_maxSize) {
            if (m_debug) {
                System.out.println("Failed to add an entry to the cache since the new entry exceeds the cache size");
            }    
            return false;
        }

        // if the size of entry being added is bigger than the spare space in the
        // cache
        if (size > m_remainingSize) {
            while (true) {
                OrderedNode removeNode = headNode;
                headNode = headNode.getNext();
                headNode.setPrev(null);
                m_cache.remove(removeNode.getKey());
                if (m_debug)
                    System.out.println("DEBUG: The spare size of the cache is not big enough "+
                        "to hold the new entry, deleting the entry whose key -> "+ removeNode.getKey());
                m_remainingSize += ((Long)
                    removeNode.getValue().elementAt(0)).longValue();
                if (m_remainingSize >= size) {
                    break;
                }
            }
        }

        m_remainingSize -= size;
        OrderedNode node = new OrderedNode(key, v, ((System.currentTimeMillis()
            + m_timeToLive) / 1000) * 1000);
        m_cache.put(key, node);
        if (tailNode != null) {
            tailNode.setNext(node);
            node.setPrev(tailNode);
            tailNode = node;
        } else {
            headNode = node;
            tailNode = node;
            scheduleTTLTimer();
        }
        if (m_debug)
        {
            System.out.println("DEBUG: Adding a new entry whose key -> "+key);
            System.out.println("DEBUG: The current number of keys in the cache "+
            m_cache.size());
        }
        return true;
    }

    /**
     * Flush entries which stayed longer or equal to the time-to-live, and
     * Set up the TTLTimer for the next flush. Called when first entry is
     * added to the cache and when the TTLTimer expires.
     */
    synchronized void scheduleTTLTimer() {        
        if (headNode == null) {
            return;
        }
        if (m_cleanUpTask == null) {
            m_cleanUpTask = new TTLTask(this);
        }
        if (m_cleanUpTask.scheduledExecutionTime() == -1) {
            timerPool.schedule(m_cleanUpTask, new Date(headNode.getTimeout()));
        }
    }        
        
    
    /**
     * Gets the number of entries being cached.
     * @return the number of entries being cached.
     */
    public int getNumEntries()
    {
        return m_cache.size();
    }

    /**
     * Get number of LDAPConnections that share this cache
     * @return Reference Count
     */
    int getRefCount() {
        return m_refCnt;
    }

    /**
     * Add a new reference to this cache.
     *
     */
    synchronized void addReference() {
        m_refCnt++;
        if (m_debug) {
            System.err.println("Cache refCnt="+ m_refCnt);
        }
    }

    /**
     * Remove a reference to this cache.
     * If the reference count is 0, cleaup the cache.
     *
     */
    synchronized void removeReference() {
        if (m_refCnt > 0) {
            m_refCnt--;
            if (m_debug) {
                System.err.println("Cache refCnt="+ m_refCnt);
            }
            if (m_refCnt == 0 ) {
                cleanup();
            }
        }
    }

    /**
     * Cleans up
     */
    synchronized void cleanup() {
        flushEntries(null, 0);
        if (m_cleanUpTask != null) {
            m_cleanUpTask.cancel();
            m_cleanUpTask = null;
        }
    }

    /**
     * Initialize the instance variables.
     */
    private void init(long ttl, long size)
    {
        m_cache = new Hashtable();
        m_timeToLive = ttl*1000;
        m_maxSize = size;
        m_remainingSize = size;
        m_dns = null;
        headNode = null;
        tailNode = null;
    }

    /**
     * Concatenates the specified integer with the delimiter.
     * @param str the String to concatenate with the delimiter
     * @return the concatenated string.
     */
    private void appendString(String str, StringBuffer buf) {
        if (str == null) {
            buf.append("null").append(DELIM);
        } else {
            buf.append(str.trim()).append(DELIM);
        }
    }

    /**
     * Concatenates the specified integer with the delimiter.
     * @param num the integer to concatenate with the delimiter
     * @return the concatenated string.
     */
    private void appendString(int num, StringBuffer buf) {
        buf.append(num).append(DELIM);
    }

    /**
     * Concatenate the specified string array with the delimiter.
     * @param str a string array
     * @return the concatenated string.
     */
    private void appendString(String[] str, StringBuffer buf) {        
        if ((str == null) || (str.length < 1)) {
            buf.append("0").append(DELIM);            
        } else {
            //String[] sorted = new String[str.length];
            //System.arraycopy( str, 0, sorted, 0, str.length );
            //sortStrings(sorted);
            sortStrings(str);
            //buf.append(sorted.length).append(DELIM);            
            buf.append(str.length).append(DELIM);
            //for (int i=0; i<sorted.length; i++) {
                //buf.append(sorted[i].trim()).append(DELIM);                
            //}            
            for (int i=0; i<str.length; i++) {
                buf.append(str[i].trim()).append(DELIM);                
            } 
        }
    }

    /**
     * Sorts the array of strings using bubble sort.
     * @param str the array of strings to sort. The str parameter contains
     * the sorted result.
     */
    private void sortStrings(String[] str) {

        for (int i=0; i<str.length; i++)
            str[i] = str[i].trim();

        for (int i=0; i<str.length-1; i++)
            for (int j=i+1; j<str.length; j++)
            {
                if (str[i].compareTo(str[j]) > 0)
                {
                    String t = str[i];
                    str[i] = str[j];
                    str[j] = t;
                }
            }
    }

    /**
     * Create a 32 bits CRC from the given byte array.
     */
    private long getCRC32(byte[] barray) {
        if (barray==null) {
            return 0;
        }
        CRC32 crcVal = new CRC32();
        crcVal.update(barray);
        return crcVal.getValue();
    }
}

class OrderedNode {
    private OrderedNode next;
    
    private OrderedNode prev;
    
    private Vector value;
    
    private Long key;
    
    private long timeout;
    
    private transient TimerPool timerPool;
    
    public OrderedNode(Long key, Vector value, long timeout) {
        this.timerPool = SystemTimerPool.getTimerPool();
        this.key = key;  
        this.timeout = timeout;
        this.value = value;
        this.prev = null;
        this.next = null;
    }
    
    public void setNext(OrderedNode next) {
        this.next = next;
    }
    
    public OrderedNode getNext() {
        return next;
    }
    
    public Vector getValue() {
        return value;
    }
    
    public Long getKey() {
        return key;
    }
    
    public void setPrev(OrderedNode prev) {
        this.prev = prev;
    }
    
    public OrderedNode getPrev() {
        return prev;
    }
    
    public long getTimeout() {
        return timeout;
    }
    
}

class TTLTask extends GeneralTaskRunnable {
    
    private LDAPCache cache;    
    private long nextRun;
    
    public TTLTask(LDAPCache cache) {
        this.cache = cache;        
        this.nextRun = -1;
    }
    
    public void run() {
        nextRun = cache.flushEntries();        
    }        
    
    public long getRunPeriod() {
        if (nextRun != -1) {
            return nextRun - scheduledExecutionTime();
        }
        return -1;
    }
    
    public boolean isEmpty() {
        return true;
    }
    
    public boolean addElement(Object obj) {
        return false;
    }
    
    public boolean removeElement(Object obj) {
        return false;
    }
    
}
        
