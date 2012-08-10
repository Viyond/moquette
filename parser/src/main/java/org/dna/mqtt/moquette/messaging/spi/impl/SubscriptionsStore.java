package org.dna.mqtt.moquette.messaging.spi.impl;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import org.dna.mqtt.moquette.MQTTException;
import org.dna.mqtt.moquette.server.MQTTHandler;
import org.fusesource.hawtbuf.codec.StringCodec;
import org.fusesource.hawtdb.api.BTreeIndexFactory;
import org.fusesource.hawtdb.api.PageFile;
import org.fusesource.hawtdb.api.PageFileFactory;
import org.fusesource.hawtdb.api.SortedIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a tree of published topics
 *
 * @author andrea
 */
public class SubscriptionsStore {

    private class TreeNode {

        TreeNode m_parent;
        Token m_token;
        List<TreeNode> m_children = new ArrayList<TreeNode>();
        List<Subscription> m_subscriptions = new ArrayList<Subscription>();

        TreeNode(TreeNode parent) {
            this.m_parent = parent;
        }

        Token getToken() {
            return m_token;
        }

        void setToken(Token topic) {
            this.m_token = topic;
        }

        void addSubcription(Subscription s) {
            //avoid double registering
            if (m_subscriptions.contains(s)) {
                return;
            }
            m_subscriptions.add(s);
        }

        void addChild(TreeNode child) {
            m_children.add(child);
        }

        boolean isLeaf() {
            return m_children.isEmpty();
        }

        /**
         * Search for children that has the specified token, if not found return
         * null;
         */
        TreeNode childWithToken(Token token) {
            for (TreeNode child : m_children) {
                if (child.getToken().equals(token)) {
                    return child;
                }
            }

            return null;
        }

        List<Subscription> subscriptions() {
            return m_subscriptions;
        }

        void matches(Queue<Token> tokens, List<Subscription> matchingSubs) {
            Token t = tokens.poll();

            //check if t is null <=> tokens finished
            if (t == null) {
                matchingSubs.addAll(m_subscriptions);
                //check if it has got a MULTI child and add its subscriptions
                for (TreeNode n : m_children) {
                    if (n.getToken() == Token.MULTI) {
                        matchingSubs.addAll(n.subscriptions());
                    }
                }

                return;
            }

            //we are on MULTI, than add subscriptions and return
            if (m_token == Token.MULTI) {
                matchingSubs.addAll(m_subscriptions);
                return;
            }

            for (TreeNode n : m_children) {
                if (n.getToken().match(t)) {
                    //Create a copy of token, alse if navigate 2 sibling it 
                    //consumes 2 elements on the queue instead of one
                    n.matches(new LinkedBlockingQueue<Token>(tokens), matchingSubs);
                }
            }
        }

        /**
         * Return the number of registered subscriptions
         */
        int size() {
            int res = m_subscriptions.size();
            for (TreeNode child : m_children) {
                res += child.size();
            }
            return res;
        }

        void removeClientSubscriptions(String clientID) {
            //collect what to delete and then delete to avoid ConcurrentModification
            List<Subscription> subsToRemove = new ArrayList<Subscription>();
            for (Subscription s : m_subscriptions) {
                if (s.clientId.equals(clientID)) {
                    subsToRemove.add(s);
                }
            }

            for (Subscription s : subsToRemove) {
                m_subscriptions.remove(s);
            }

            //go deep
            for (TreeNode child : m_children) {
                child.removeClientSubscriptions(clientID);
            }
        }
    }

    protected static class Token {

        static final Token EMPTY = new Token("");
        static final Token MULTI = new Token("#");
        static final Token SINGLE = new Token("+");
        String name;

        protected Token(String s) {
            name = s;
        }

        protected String name() {
            return name;
        }

        protected boolean match(Token t) {
            if (t == MULTI || t == SINGLE) {
                return false;
            }

            if (this == MULTI || this == SINGLE) {
                return true;
            }

            return equals(t);
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 29 * hash + (this.name != null ? this.name.hashCode() : 0);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Token other = (Token) obj;
            if ((this.name == null) ? (other.name != null) : !this.name.equals(other.name)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return name;
        }
    }
//    private List<Subscription> subscriptions = new ArrayList<Subscription>();
    private TreeNode subscriptions = new TreeNode(null);
    
    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionsStore.class);
    
    //pesistent Map of clientID, list of Subscriptions
    private SortedIndex<String, List<Subscription>> m_persistent;
    private PageFile pageFile;

    /**
     * Initialize basic store structures, like the FS storage to maintain
     * client's topics subscriptions
     */
    public void init() {
        PageFileFactory factory = new PageFileFactory();
        File tmpFile;
        try {
            tmpFile = File.createTempFile("hawtdb", "test");
        } catch (IOException ex) {
            LOG.error(null, ex);
            throw new MQTTException("Can't create temp file for subscriptions storage", ex);
        }
        factory.setFile(/*new File("mydb.dat")*/tmpFile);
        factory.open();
        pageFile = factory.getPageFile();

//        HashIndexFactory<String, List<Subscription>> indexFactory = 
//                new HashIndexFactory<String, List<Subscription>>();
        BTreeIndexFactory<String, List<Subscription>> indexFactory = 
                new BTreeIndexFactory<String, List<Subscription>>();
        indexFactory.setKeyCodec(StringCodec.INSTANCE);
        
        m_persistent = indexFactory.openOrCreate(pageFile);
        
        //reaload any subscriptions persisted
        LOG.debug("Reloading all stored subscriptions...");
        for(Map.Entry<String, List<Subscription>> entry: m_persistent) {
            for (Subscription subscription : entry.getValue()) {
                addDirect(subscription);
            }
        }
        LOG.debug("Finished loading");
    }
    
    
    protected void addDirect(Subscription newSubscription) {
        List<Token> tokens = new ArrayList<Token>();
        try {
            tokens = splitTopic(newSubscription.topic);
        } catch (ParseException ex) {
            //TODO handle the parse exception
            LOG.error(null, ex);
        }

        TreeNode current = subscriptions;
        for (Token token : tokens) {
            TreeNode matchingChildren;

            //check if a children with the same token already exists
            if ((matchingChildren = current.childWithToken(token)) != null) {
                current = matchingChildren;
            } else {
                //create a new node for the newly inserted token
                matchingChildren = new TreeNode(current);
                matchingChildren.setToken(token);
                current.addChild(matchingChildren);
                current = matchingChildren;
            }
        }
        current.addSubcription(newSubscription);
    }

    public void add(Subscription newSubscription) {
        addDirect(newSubscription);

        //log the subscription
        String clientID = newSubscription.getClientId();
        if (!m_persistent.containsKey(clientID)) {
            m_persistent.put(clientID, new ArrayList<Subscription>());
        }
        
        List<Subscription> subs = m_persistent.get(clientID);
        subs.add(newSubscription);
        pageFile.flush();
    }

    /**
     * Visit the topics tree to remove matching subscriptions with clientID
     */
    public void removeForClient(String clientID) {
        subscriptions.removeClientSubscriptions(clientID);
        
        //remove from log all subscriptions
        m_persistent.remove(clientID);
    }

    public List<Subscription> matches(String topic) {
        List<Token> tokens = new ArrayList<Token>();
        try {
            tokens = splitTopic(topic);
        } catch (ParseException ex) {
            //TODO handle the parse exception
            LOG.error(null, ex);
        }

        Queue<Token> tokenQueue = new LinkedBlockingDeque<Token>(tokens);
        List<Subscription> matchingSubs = new ArrayList<Subscription>();
        subscriptions.matches(tokenQueue, matchingSubs);
        return matchingSubs;
    }

    public boolean contains(Subscription sub) {
        return !matches(sub.topic).isEmpty();
    }

    public int size() {
        return subscriptions.size();
    }

    protected List<Token> splitTopic(String topic) throws ParseException {
        List res = new ArrayList<Token>();
        String[] splitted = topic.split("/");

        if (splitted.length == 0) {
            res.add(Token.EMPTY);
        }

        for (int i = 0; i < splitted.length; i++) {
            String s = splitted[i];
            if (s.isEmpty()) {
                if (i != 0) {
                    throw new ParseException("Bad format of topic, expetec topic name between separators", i);
                }
                res.add(Token.EMPTY);
            } else if (s.equals("#")) {
                //check that multi is the last symbol
                if (i != splitted.length - 1) {
                    throw new ParseException("Bad format of topic, the multi symbol (#) has to be the last one after a separator", i);
                }
                res.add(Token.MULTI);
            } else if (s.contains("#")) {
                throw new ParseException("Bad format of topic, invalid subtopic name: " + s, i);
            } else if (s.equals("+")) {
                res.add(Token.SINGLE);
            } else if (s.contains("+")) {
                throw new ParseException("Bad format of topic, invalid subtopic name: " + s, i);
            } else {
                res.add(new Token(s));
            }
        }

        return res;
    }
}
