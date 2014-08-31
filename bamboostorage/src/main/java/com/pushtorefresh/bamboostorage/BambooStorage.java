package com.pushtorefresh.bamboostorage;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Artem Zinnatullin [artem.zinnatullin@gmail.com]
 */
public class BambooStorage {

    /**
     * Statement: where internal id = ?
     */
    private static final String WHERE_ID = BaseColumns._ID + " = ?";

    /**
     * Content path string for building uris for requests
     */
    private final String mContentPath;

    /**
     * Content resolver for work with content provider
     */
    private final ContentResolver mContentResolver;

    /**
     * Resources for StorableItem.toContentValues() calls
     */
    private final Resources mResources;

    /**
     * Thread-safe cache of (StorableItemClass, ContentPath) pairs for better performance
     */
    private final Map<Class<? extends StorableItem>, String> cacheOfClassAndContentPathPairs = new ConcurrentHashMap<Class<? extends StorableItem>, String>();

    public BambooStorage(@NonNull Context context, @NonNull String contentProviderAuthority) {
        mContentPath     = "content://" + contentProviderAuthority + "/%s";
        mContentResolver = context.getContentResolver();
        mResources       = context.getResources();
    }

    /**
     * Adds storableItem to the Storage
     * @param storableItem to add
     */
    public void add(@NonNull StorableItem storableItem) {
        Uri uri = mContentResolver.insert(buildUri(storableItem.getClass()), storableItem.toContentValues(mResources));
        storableItem.set_id(ContentUris.parseId(uri));
    }

    /**
     * Updates storable item in the storage
     * @param storableItem to update
     * @return count of updated items
     * @throws IllegalArgumentException if storable item internal id <= 0 -> it was not stored in StorageManager
     */
    public int update(@NonNull StorableItem storableItem) {
        long itemInternalId = storableItem.get_id();

        if (itemInternalId <= 0) {
            throw new IllegalArgumentException("Item: " + storableItem + " can not be updated, because its internal id is <= 0");
        } else {
            return mContentResolver.update(
                    buildUri(storableItem.getClass()),
                    storableItem.toContentValues(mResources),
                    WHERE_ID,
                    buildWhereArgsByInternalId(storableItem)
            );
        }
    }

    /**
     * Adds or updates storable item to/in storage
     * @param storableItem to add or update
     * @return true if item was added, false if item was updated
     */
    public boolean addOrUpdate(@NonNull StorableItem storableItem) {
        if (storableItem.get_id() <= 0) {
            // item was not stored in the storage
            add(storableItem);
            return true;
        } else {
            update(storableItem);
            return false;
        }
    }

    /**
     * Gets stored item by its internal id
     * @param classOfStorableItem class of storable item
     * @param itemInternalId internal id of the item you want to getByInternalId from storage
     * @param <T> generic type of StorableItem
     * @return storable item with required internal id or null if storage does not contain this item
     */
    public <T extends StorableItem> T getByInternalId(@NonNull Class<T> classOfStorableItem, long itemInternalId) {
        Cursor cursor = getAsCursor(classOfStorableItem, WHERE_ID, buildWhereArgsByInternalId(itemInternalId), null);

        try {
            if (cursor != null && cursor.moveToFirst()) {
                return createStorableItemFromCursor(classOfStorableItem, cursor);
            } else {
                return null;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Gets all stored items of required type which satisfies where condition as list in memory
     * @param classOfStorableItem class of StorableItem
     * @param where where clause
     * @param whereArgs args for binding to where clause, same format as for ContentResolver
     * @param orderBy order by clause
     * @param <T> generic type of StorableItem
     * @return list of StorableItems, can be empty but not null
     */
    @NonNull
    public <T extends StorableItem> List<T> getAsList(@NonNull Class<T> classOfStorableItem, @Nullable String where, @Nullable String[] whereArgs, @Nullable String orderBy) {
        Cursor cursor = getAsCursor(classOfStorableItem, where, whereArgs, orderBy);

        List<T> list = new ArrayList<T>(cursor == null ? 0 : cursor.getCount());

        if (cursor == null || !cursor.moveToFirst()) {
            return list;
        }

        try {
            do {
                list.add(createStorableItemFromCursor(classOfStorableItem, cursor));
            } while (cursor.moveToNext());
        } finally {
            cursor.close();
        }

        return list;
    }

    /**
     * Gets all stored items of required type which satisfies where condition as list in memory
     *
     * NOTICE: orderBy is null, so it's default for your type of storage in ContentProvider
     *
     * @param classOfStorableItem class of StorableItem
     * @param where where clause
     * @param whereArgs args for binding to where clause, same format as for ContentResolver
     * @param <T> generic type of StorableItem
     * @return list of StorableItems, can be empty but not null
     */
    public <T extends StorableItem> List<T> getAsList(@NonNull Class<T> classOfStorableItem, @Nullable String where, @Nullable String[] whereArgs) {
        return getAsList(classOfStorableItem, where, whereArgs, null);
    }

    /**
     * Gets all stored items of required type with default order as list in memory
     * @param classOfStorableItem class of StorableItem
     * @param <T> generic type of StorableItem
     * @return list of StorableItems, can be empty but not null
     */
    @NonNull
    public <T extends StorableItem> List<T> getAsList(@NonNull Class<T> classOfStorableItem) {
        return getAsList(classOfStorableItem, null, null);
    }

    /**
     * Gets all stored items of required type which satisfies where condition as cursor
     * @param classOfStorableItem class of StorableItem
     * @param where where clause
     * @param whereArgs args for binding to where clause, same format as for ContentResolver
     * @param orderBy order by clause
     * @return cursor with query result, can be null
     */
    @Nullable
    public Cursor getAsCursor(@NonNull Class<? extends StorableItem> classOfStorableItem, @Nullable String where, @Nullable String[] whereArgs, @Nullable String orderBy) {
        return mContentResolver.query(
                buildUri(classOfStorableItem),
                null,
                where,
                whereArgs,
                orderBy
        );
    }

    /**
     * Gets first item from the query result
     * @param classOfStorableItem class of StorableItem
     * @param where where clause
     * @param whereArgs args for binding to where clause, same format as for ContentResolver
     * @param orderBy order by clause
     * @param <T> generic type of StorableItem
     * @return first item in the query result or null if there are no query results
     */
    @Nullable
    public <T extends StorableItem> T getFirst(@NonNull Class<T> classOfStorableItem, @Nullable String where, @Nullable String[] whereArgs, @Nullable String orderBy) {
        Cursor cursor = getAsCursor(classOfStorableItem, where, whereArgs, orderBy);

        try {
            if (cursor != null && cursor.moveToFirst()) {
                return createStorableItemFromCursor(classOfStorableItem, cursor);
            } else {
                return null;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Gets first item of required type with default order
     * @param classOfStorableItem class of StorableItem
     * @param <T> generic type of StorableItem
     * @return first item of required type, can be null
     */
    @Nullable
    public <T extends StorableItem> T getFirst(@NonNull Class<T> classOfStorableItem) {
        return getFirst(classOfStorableItem, null, null, null);
    }

    /**
     * Gets last item from the query result
     *
     * It's pretty fast implementation based on cursor using, it's memory and speed efficiently
     *
     * @param classOfStorableItem class of StorableItem
     * @param where where clause
     * @param whereArgs args for binding to where clause, same format as for ContentResolver
     * @param orderBy order by clause
     * @param <T> generic type of StorableItem
     * @return last item in the query result or null if there are no query results
     */
    @Nullable
    public <T extends StorableItem> T getLast(@NonNull Class<T> classOfStorableItem, @Nullable String where, @Nullable String[] whereArgs, @Nullable String orderBy) {
        Cursor cursor = getAsCursor(classOfStorableItem, where, whereArgs, orderBy);

        try {
            if (cursor != null && cursor.moveToFirst()) {

                while (true) {
                    if (!cursor.moveToNext()) {
                        break;
                    }
                }

                cursor.moveToPrevious();

                return createStorableItemFromCursor(classOfStorableItem, cursor);
            } else {
                return null;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Gets last item of required type with default order
     * @param classOfStorableItem class of StorableItem
     * @param <T> generic type of StorableItem
     * @return last item of required type, can be null
     */
    @Nullable
    public <T extends StorableItem> T getLast(@NonNull Class<T> classOfStorableItem) {
        return getLast(classOfStorableItem, null, null, null);
    }

    /**
     * Removes storable item from storage
     * @param storableItem item to be removed from the storage
     * @return count of removed items, it could be 0, or 1, or > 1 if you have items with same internal id in the storage
     */
    public int remove(@NonNull StorableItem storableItem) {
        return remove(storableItem.getClass(), WHERE_ID, buildWhereArgsByInternalId(storableItem));
    }

    /**
     * Removes all storable items of required type which matched where condition
     * @param classOfStorableItems type of storable item you want to delete
     * @param where where clause
     * @param whereArgs args for binding to where clause, same format as for ContentResolver
     * @return count of removed items
     */
    public int remove(@NonNull Class<? extends StorableItem> classOfStorableItems, String where, String[] whereArgs) {
        return mContentResolver.delete(buildUri(classOfStorableItems), where, whereArgs);
    }

    /**
     * Removes all storable items of required type
     * Same as calling remove(class, null, null)
     * @param classOfStorableItems type of storable item you want to delete
     * @return count of removed items
     */
    public int removeAllOfType(@NonNull Class<? extends StorableItem> classOfStorableItems) {
        return remove(classOfStorableItems, null, null);
    }

    /**
     * Checks "is item currently stored in storage"
     * @param storableItem to check
     * @return true if item stored in storage, false if not
     */
    public boolean contains(@NonNull StorableItem storableItem) {
        Cursor cursor = getAsCursor(storableItem.getClass(), WHERE_ID, buildWhereArgsByInternalId(storableItem), null);

        if (cursor == null || !cursor.moveToFirst()) {
            return false;
        } else {
            cursor.close();
            return true;
        }
    }

    /**
     * Returns count of items in the storage of required type
     * @param classOfStorableItems type of storable item you want to count
     * @return count of items in the storage of required type
     */
    public int countOfItems(@NonNull Class<? extends StorableItem> classOfStorableItems) {
        Cursor cursor = getAsCursor(classOfStorableItems, null, null, null);

        try {
            return cursor != null ? cursor.getCount() : 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Notifying about change in the storage using content resolver notifyChange method
     * @param classOfStorableItem class of StorableItem
     * @param contentObserver The observer that originated the change, may be <code>null</null>.
     * The observer that originated the change will only receive the notification if it
     * has requested to receive self-change notifications by implementing
     */
    public void notifyChange(@NonNull Class<? extends StorableItem> classOfStorableItem, @Nullable ContentObserver contentObserver) {
        mContentResolver.notifyChange(buildUri(classOfStorableItem), contentObserver);
    }

    /**
     * Notifying about change in the storage using content resolver notifyChange method
     * @param classOfStorableItem class of StorableItem
     */
    public void notifyChange(@NonNull Class<? extends StorableItem> classOfStorableItem) {
        mContentResolver.notifyChange(buildUri(classOfStorableItem), null);
    }

    /**
     * Builds uri for accessing content
     * @param clazz of the content for building uri
     * @return Uri for accessing content
     */
    @NonNull
    private Uri buildUri(@NonNull Class<? extends StorableItem> clazz) {
        String contentPath = cacheOfClassAndContentPathPairs.get(clazz);

        if (contentPath == null) {
            if (!clazz.isAnnotationPresent(ContentPathForContentResolver.class)) {
                throw new IllegalArgumentException("Class " + clazz + " should be marked with " + ContentPathForContentResolver.class + " annotation");
            }

            ContentPathForContentResolver tableRepresentation = clazz.getAnnotation(ContentPathForContentResolver.class);

            contentPath = tableRepresentation.value();

            cacheOfClassAndContentPathPairs.put(clazz, contentPath);
        }

        return Uri.parse(String.format(mContentPath, contentPath));
    }

    /**
     * Builds where args with storable item id for common requests to the content resolver
     * @param internalStorableItemId internal id of storable item
     * @return where args
     */
    private static String[] buildWhereArgsByInternalId(long internalStorableItemId) {
        return new String[] { String.valueOf(internalStorableItemId) };
    }

    /**
     * Builds where args with storable item id for common requests to the content resolver
     * @param storableItem to get internal id
     * @return where args
     */
    private static String[] buildWhereArgsByInternalId(@NonNull StorableItem storableItem) {
        return buildWhereArgsByInternalId(storableItem.get_id());
    }

    /**
     * Creates and fills storable item from cursor
     * @param classOfStorableItem class of storable item to instantiate
     * @param cursor cursor to getByInternalId fields of item
     * @param <T> generic type of the storable item
     * @return storable item filled with info from cursor
     * @throws IllegalArgumentException if classOfStorableItem can not be used to create item from Cursor
     */
    @NonNull
    public static <T extends StorableItem> T createStorableItemFromCursor(@NonNull Class<T> classOfStorableItem, @NonNull Cursor cursor) {
        try {
            T storableItem = classOfStorableItem.newInstance();
            storableItem._fillFromCursor(cursor);
            return storableItem;
        } catch (InstantiationException e) {
            throw new IllegalArgumentException(classOfStorableItem + " can not be used for createStorableItemFromCursor() because its instance can not be created by class.newInstance()");
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(classOfStorableItem + " can not be used for createStorableItemFromCursor() because it had no default constructor or it's not public, instance can not be created by class.newInstance()");
        }
    }
}
