/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Common Public License 1.0 (http://opensource.org/licenses/cpl.php)
 *   which can be found in the file CPL.TXT at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich May 30, 2006 */

package org.clojure.runtime;

import java.util.*;

public class Transaction{

public static final int COMMITTED = 0;
public static final int WORKING = 1;
static final Object lock = new Object();

volatile static int nextSeq = 1;

static int getNextSeq(){
	synchronized(lock){
		return nextSeq++;
	}
}

public static class Info{
int seq;
int status;


Info(int seq,int status){
	this.seq = seq;
	this.status = status;
}
}


Info info;
int startSeq;

IdentityHashMap<TRef,Object> sets;
IdentityHashMap<TRef,ISeq> commutates;


static public Object runInTransaction(ThreadLocalData tld,IFn fn) throws Exception{
	if(tld.transaction != null)
		return fn.invoke(tld);
	tld.transaction = new Transaction();
	try{
		return tld.transaction.run(tld, fn);
		}
	finally{
		tld.transaction = null;
		}
}

static public TRef tref(ThreadLocalData tld, Object val) throws Exception{
	Transaction trans = tld.getTransaction();
	TRef tref = new TRef();
	trans.set(tref, val);
	return tref;
}

static public Object get(ThreadLocalData tld, TRef tref) throws Exception{
	 return tld.getTransaction().get(tref);
}

static public Object set(ThreadLocalData tld, TRef tref, Object val) throws Exception{
	 return tld.getTransaction().set(tref,val);
}

static public void touch(ThreadLocalData tld, TRef tref) throws Exception{
	tld.getTransaction().touch(tref);
}

static public void commutate(ThreadLocalData tld, TRef tref, IFn fn) throws Exception{
	tld.getTransaction().commutate(tref, fn);
}


Object run(ThreadLocalData tld, IFn fn) throws Exception{
	boolean done = false;
	Object ret = null;
	ArrayList<TRef> locks = null;
	ArrayList<TRef> locked = null;

	loop:
	while(!done){
		try
			{
			ret = fn.invoke(tld);
			if(locks == null && (sets != null || commutates != null))
				locks = new ArrayList<TRef>();
			if(sets != null)
				locks.addAll(sets.keySet());
			if(commutates != null)
				locks.addAll(commutates.keySet());
			if(locks != null)
				{
				if(locked == null)
					locked = new ArrayList<TRef>(locks.size());
				//lock in order, to avoid deadlocks
				Collections.sort(locks);
				for(TRef tref : locks)
					{
					//will block here
					tref.lock.lock();
					locked.add(tref);
					if(sets.containsKey(tref))
						{
						//try again if the thing we are trying to set has changed since we started
						TVal curr = getCurrent(tref);
						if(curr != null && curr.tinfo.seq > startSeq)
							continue loop;
						}
					}
				}

			//at this point all write targets are locked
			//turn commutates into sets
			for(Map.Entry<TRef, ISeq> e : commutates.entrySet())
				{
				TRef tref = e.getKey();
				//note this will npe if tref has never been set, as designed
				Object val = getCurrent(tref).val;
				for(ISeq c = e.getValue();c!=null;c = c.rest())
					{
					IFn f = (IFn) c.first();
					val = f.invoke(tld, val);
					}
				sets.put(tref, val);
				}

			//set the new vals
			for(Map.Entry<TRef, Object> entry : sets.entrySet())
				{
				TRef tref = entry.getKey();
				tref.push(entry.getValue(), info);
				}

			//atomic commit
			synchronized(lock){
				info.seq = getNextSeq();
				info.status = COMMITTED;
			}

			done = true;
			}
		finally{
			if(locked != null)
				{
				for(TRef tref : locked)
					{
					tref.lock.unlock();
					}
				locked.clear();
				}
			reset();
			if(locks != null)
				locks.clear();
			}
		}
	return ret;
}

private void reset(){
	if(sets != null)
		sets.clear();
	if(commutates != null)
		commutates.clear();

}


Transaction(){
	synchronized(lock){
		int seq = getNextSeq();
		this.info = new Info(seq, WORKING);
		this.startSeq = seq;
	}
}

Object get(TRef tref) throws Exception{
	if(sets != null && sets.containsKey(tref))
		return sets.get(tref);

    for(TVal ver = tref;ver != null;ver = ver.prior)
	    {
	    //note this will npe if tref has never been set, as designed
	    if(ver.tinfo.status == COMMITTED && ver.tinfo.seq <= startSeq)
		    return ver.val;
	    }

	throw new Exception("Version not found");
}

static TVal getCurrent(TRef tref) throws Exception{
    for(TVal ver = tref;ver != null;ver = ver.prior)
	    {
	    if(ver.tinfo != null && ver.tinfo.status == COMMITTED)
		    return ver;
	    }
	//this return only if no value was ever successfully set
	return null;
}

Object set(TRef tref, Object val) throws Exception{
	if(sets == null)
		sets = new IdentityHashMap<TRef,Object>();
	if(commutates != null && commutates.containsKey(tref))
		throw new Exception("Can't commutate and set a TRef in the same transaction");

	sets.put(tref,val);
	return val;
	}

void touch(TRef tref) throws Exception{
	set(tref, get(tref));
	}

void commutate(TRef tref, IFn fn) throws Exception{
	if(commutates == null)
		commutates = new IdentityHashMap<TRef,ISeq>();
	if(sets != null && sets.containsKey(tref))
		throw new Exception("Can't commutate and set a TRef in the same transaction");
	commutates.put(tref, RT.cons(fn, commutates.get(tref)));
	}

}