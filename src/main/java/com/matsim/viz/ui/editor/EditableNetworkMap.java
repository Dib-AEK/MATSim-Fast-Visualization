package com.matsim.viz.ui.editor;

import java.util.*;

/** A small edit layer over immutable loaded records; opening an editor copies no network maps. */
final class EditableNetworkMap<K,V> extends AbstractMap<K,V> {
    private final Map<K,V> original;
    private final Map<K,V> changes=new LinkedHashMap<>();
    private final Set<K> removed=new HashSet<>();
    EditableNetworkMap(Map<K,V> original){this.original=original;}
    @Override public V get(Object key){if(key==null)return null;return changes.containsKey(key)?changes.get(key):removed.contains(key)?null:original.get(key);}
    @Override public boolean containsKey(Object key){if(key==null)return false;return changes.containsKey(key)||!removed.contains(key)&&original.containsKey(key);}
    @Override public V put(K key,V value){V before=get(key);removed.remove(key);changes.put(key,Objects.requireNonNull(value));return before;}
    @Override public V remove(Object key){if(key==null)return null;V before=get(key);changes.remove(key);if(original.containsKey(key)){@SuppressWarnings("unchecked")K k=(K)key;removed.add(k);}return before;}
    @Override public int size(){int added=0;for(K key:changes.keySet())if(!original.containsKey(key))added++;return original.size()-removed.size()+added;}
    @Override public Set<Entry<K,V>> entrySet(){
        return new AbstractSet<>(){
            public int size(){return EditableNetworkMap.this.size();}
            public Iterator<Entry<K,V>> iterator(){
                Iterator<Entry<K,V>> base=original.entrySet().iterator(),edited=changes.entrySet().iterator();
                return new Iterator<>(){
                    Entry<K,V> next;
                    public boolean hasNext(){
                        if(next!=null)return true;
                        while(base.hasNext()){var candidate=base.next();if(!removed.contains(candidate.getKey())&&!changes.containsKey(candidate.getKey())){next=candidate;return true;}}
                        if(edited.hasNext()){next=edited.next();return true;}return false;
                    }
                    public Entry<K,V> next(){if(!hasNext())throw new NoSuchElementException();var entry=next;next=null;return new SimpleImmutableEntry<>(entry);}
                };
            }
        };
    }
}
