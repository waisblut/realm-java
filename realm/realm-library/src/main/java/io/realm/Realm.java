/*
 * Copyright 2014 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm;

import android.annotation.TargetApi;
import android.app.IntentService;
import android.os.Build;
import android.util.JsonReader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Future;

import io.realm.annotations.internal.OptionalAPI;
import io.realm.exceptions.RealmException;
import io.realm.exceptions.RealmIOException;
import io.realm.exceptions.RealmMigrationNeededException;
import io.realm.internal.ColumnIndices;
import io.realm.internal.ColumnInfo;
import io.realm.internal.RealmObjectProxy;
import io.realm.internal.RealmProxyMediator;
import io.realm.internal.Table;
import io.realm.internal.Util;
import io.realm.internal.log.RealmLog;
import rx.Observable;

/**
 * The Realm class is the storage and transactional manager of your object persistent store. It is in charge of creating
 * instances of your RealmObjects. Objects within a Realm can be queried and read at any time. Creating, modifying, and
 * deleting objects must be done while inside a transaction. See {@link #executeTransaction(Transaction)}
 * <p>
 * The transactions ensure that multiple instances (on multiple threads) can access the same objects in a consistent
 * state with full ACID guarantees.
 * <p>
 * It is important to remember to call the {@link #close()} method when done with a Realm instance. Failing to do so can
 * lead to {@link java.lang.OutOfMemoryError} as the native resources cannot be freed.
 * <p>
 * Realm instances cannot be used across different threads. This means that you have to open an instance on each thread
 * you want to use Realm. Realm instances are cached automatically per thread using reference counting, so as long as
 * the reference count doesn't reach zero, calling {@link #getInstance(RealmConfiguration)} will just return the cached
 * Realm and should be considered a lightweight operation.
 * <p>
 * For the UI thread this means that opening and closing Realms should occur in either onCreate/onDestroy or
 * onStart/onStop.
 * <p>
 * Realm instances coordinate their state across threads using the {@link android.os.Handler} mechanism. This also means
 * that Realm instances on threads without a {@link android.os.Looper} cannot receive updates unless {@link #waitForChange()}
 * is manually called.
 * <p>
 * A standard pattern for working with Realm in Android activities can be seen below:
 * <p>
 * <pre>
 * public class RealmApplication extends Application {
 *
 *     \@Override
 *     public void onCreate() {
 *         super.onCreate();
 *
 *         // The Realm file will be located in package's "files" directory.
 *         RealmConfiguration realmConfig = new RealmConfiguration.Builder(this).build();
 *         Realm.setDefaultConfiguration(realmConfig);
 *     }
 * }
 *
 * public class RealmActivity extends Activity {
 *
 *   private Realm realm;
 *
 *   \@Override
 *   protected void onCreate(Bundle savedInstanceState) {
 *     super.onCreate(savedInstanceState);
 *     setContentView(R.layout.layout_main);
 *     realm = Realm.getDefaultInstance();
 *   }
 *
 *   \@Override
 *   protected void onDestroy() {
 *     super.onDestroy();
 *     realm.close();
 *   }
 * }
 * </pre>
 * <p>
 * Realm supports String and byte fields containing up to 16 MB.
 * <p>
 *
 * @see <a href="http://en.wikipedia.org/wiki/ACID">ACID</a>
 * @see <a href="https://github.com/realm/realm-java/tree/master/examples">Examples using Realm</a>
 */
public final class Realm extends BaseRealm {

    public static final String DEFAULT_REALM_NAME = RealmConfiguration.DEFAULT_REALM_NAME;

    // Caches Class objects (both model classes and proxy classes) to Realm Tables
    private final Map<Class<? extends RealmModel>, Table> classToTable =
            new HashMap<Class<? extends RealmModel>, Table>();

    private static RealmConfiguration defaultConfiguration;

    /**
     * The constructor is private to enforce the use of the static one.
     *
     * @param configuration the {@link RealmConfiguration} used to open the Realm.
     * @throws IllegalArgumentException if trying to open an encrypted Realm with the wrong key.
     */
    Realm(RealmConfiguration configuration) {
        super(configuration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @OptionalAPI(dependencies = {"rx.Observable"})
    public Observable<Realm> asObservable() {
        return configuration.getRxFactory().from(this);
    }

    /**
     * Realm static constructor that returns the Realm instance defined by the {@link io.realm.RealmConfiguration} set
     * by {@link #setDefaultConfiguration(RealmConfiguration)}
     *
     * @return an instance of the Realm class.
     * @throws java.lang.NullPointerException if no default configuration has been defined.
     * @throws RealmMigrationNeededException if no migration has been provided by the default configuration and the
     *         RealmObject classes or version has has changed so a migration is required.
     * @throws RealmIOException if an error happened when accessing the underlying Realm file.
     */
    public static Realm getDefaultInstance() {
        if (defaultConfiguration == null) {
            throw new NullPointerException("No default RealmConfiguration was found. Call setDefaultConfiguration() first");
        }
        return RealmCache.createRealmOrGetFromCache(defaultConfiguration, Realm.class);
    }

    /**
     * Realm static constructor that returns the Realm instance defined by provided {@link io.realm.RealmConfiguration}
     *
     * @param configuration {@link RealmConfiguration} used to open the Realm
     * @return an instance of the Realm class
     * @throws RealmMigrationNeededException if no migration has been provided by the configuration and the RealmObject
     *         classes or version has has changed so a migration is required.
     * @throws RealmIOException if an error happened when accessing the underlying Realm file.
     * @throws IllegalArgumentException if a null {@link RealmConfiguration} is provided.
     * @see RealmConfiguration for details on how to configure a Realm.
     */
    public static Realm getInstance(RealmConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("A non-null RealmConfiguration must be provided");
        }
        return RealmCache.createRealmOrGetFromCache(configuration, Realm.class);
    }

    /**
     * Sets the {@link io.realm.RealmConfiguration} used when calling {@link #getDefaultInstance()}.
     *
     * @param configuration the {@link io.realm.RealmConfiguration} to use as the default configuration.
     * @throws IllegalArgumentException if a null {@link RealmConfiguration} is provided.
     * @see RealmConfiguration for details on how to configure a Realm.
     */
    public static void setDefaultConfiguration(RealmConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("A non-null RealmConfiguration must be provided");
        }
        defaultConfiguration = configuration;
    }

    /**
     * Removes the current default configuration (if any). Any further calls to {@link #getDefaultInstance()} will
     * fail until a new default configuration has been set using {@link #setDefaultConfiguration(RealmConfiguration)}.
     */
    public static void removeDefaultConfiguration() {
        defaultConfiguration = null;
    }

    /**
     * Creates a {@link Realm} instance without checking the existence in the {@link RealmCache}.
     *
     * @param configuration {@link RealmConfiguration} used to create the Realm.
     * @param columnIndices if this is not  {@code null}, the {@link BaseRealm#schema#columnIndices} will be
     *                      initialized to it. Otherwise, {@link BaseRealm#schema#columnIndices} will be populated from
     *                      the Realm file.
     * @return a {@link Realm} instance.
     */
    static Realm createInstance(RealmConfiguration configuration, ColumnIndices columnIndices) {
        return createAndValidate(configuration, columnIndices);
    }

    static Realm createAndValidate(RealmConfiguration configuration, ColumnIndices columnIndices) {
        Realm realm = new Realm(configuration);
/*        long currentVersion = realm.getVersion();
        long requiredVersion = configuration.getSchemaVersion();
        if (currentVersion != UNVERSIONED && currentVersion < requiredVersion && columnIndices == null) {
            realm.doClose();
            throw new RealmMigrationNeededException(configuration.getPath(), String.format("Realm on disk need to migrate from v%s to v%s", currentVersion, requiredVersion));
        }
        if (currentVersion != UNVERSIONED && requiredVersion < currentVersion && columnIndices == null) {
            realm.doClose();
            throw new IllegalArgumentException(String.format("Realm on disk is newer than the one specified: v%s vs. v%s", currentVersion, requiredVersion));
        }
*/
        // Initialize Realm schema if needed
        if (columnIndices == null) {
            try {
                initializeRealm(realm);
            } catch (RuntimeException e) {
                realm.doClose();
                throw e;
            }
        } else {
            realm.schema.columnIndices = columnIndices;
        }
        try {
            initializeRealm(realm);
        } catch (RuntimeException e) {
            realm.doClose();
            throw e;
        }

        return realm;
    }

    @SuppressWarnings("unchecked")
    private static void initializeRealm(Realm realm) {
        long version = realm.getVersion();
        ArrayList<RealmObjectSchema> realmObjectSchemas = new ArrayList<>();
        RealmProxyMediator mediator = realm.configuration.getSchemaMediator();
        final Set<Class<? extends RealmModel>> modelClasses = mediator.getModelClasses();
        final Map<Class<? extends RealmModel>, ColumnInfo> columnInfoMap;
        columnInfoMap = new HashMap<Class<? extends RealmModel>, ColumnInfo>(modelClasses.size());
        for (Class<? extends RealmModel> modelClass : modelClasses) {
            if (version == UNVERSIONED) {
                realmObjectSchemas.add(mediator.createRealmObjectSchema(modelClass, realm.getSchema()));
            }
            columnInfoMap.put(modelClass, mediator.validateTable(modelClass, realm.sharedRealm));
        }
        realm.schema.columnIndices = new ColumnIndices(columnInfoMap);
        RealmSchema schema = new RealmSchema(realm, realmObjectSchemas);
        realm.sharedRealm.updateSchema(schema, realm.configuration.getSchemaVersion(), realm.configuration.getMigration());
        if (version == UNVERSIONED) {
            final Transaction transaction = realm.getConfiguration().getInitialDataTransaction();
            if (transaction != null) {
                transaction.execute(realm);
            }
        }
    }

    /**
     * Creates a Realm object for each object in a JSON array. This must be done within a transaction.
     * <p>
     * JSON properties with {@code null} values will map to the default value for the data type in Realm and unknown properties
     * will be ignored. If a {@link RealmObject} field is not present in the JSON object the {@link RealmObject}
     * field will be set to the default value for that type.
     *
     * @param clazz type of Realm objects to create.
     * @param json an array where each JSONObject must map to the specified class.
     * @throws RealmException if mapping from JSON fails.
     */
    public <E extends RealmModel> void createAllFromJson(Class<E> clazz, JSONArray json) {
        if (clazz == null || json == null) {
            return;
        }

        for (int i = 0; i < json.length(); i++) {
            try {
                configuration.getSchemaMediator().createOrUpdateUsingJsonObject(clazz, this, json.getJSONObject(i), false);
            } catch (JSONException e) {
                throw new RealmException("Could not map JSON", e);
            }
        }
    }

    /**
     * Tries to update a list of existing objects identified by their primary key with new JSON data. If an existing
     * object could not be found in the Realm, a new object will be created. This must happen within a transaction.
     * If updating a {@link RealmObject} and a field is not found in the JSON object, that field will not be updated. If
     * a new {@link RealmObject} is created and a field is not found in the JSON object, that field will be assigned the
     * default value for the field type.
     *
     * @param clazz type of {@link io.realm.RealmObject} to create or update. It must have a primary key defined.
     * @param json array with object data.
     * @throws java.lang.IllegalArgumentException if trying to update a class without a
     *         {@link io.realm.annotations.PrimaryKey}.
     * @throws RealmException if unable to map JSON.
     * @see #createAllFromJson(Class, org.json.JSONArray)
     */
    public <E extends RealmModel> void createOrUpdateAllFromJson(Class<E> clazz, JSONArray json) {
        if (clazz == null || json == null) {
            return;
        }
        checkHasPrimaryKey(clazz);
        for (int i = 0; i < json.length(); i++) {
            try {
                configuration.getSchemaMediator().createOrUpdateUsingJsonObject(clazz, this, json.getJSONObject(i), true);
            } catch (JSONException e) {
                throw new RealmException("Could not map JSON", e);
            }
        }
    }

    /**
     * Creates a Realm object for each object in a JSON array. This must be done within a transaction.
     * JSON properties with {@code null} values will map to the default value for the data type in Realm and unknown properties
     * will be ignored. If a {@link RealmObject} field is not present in the JSON object the {@link RealmObject} field
     * will be set to the default value for that type.
     *
     * @param clazz type of Realm objects to create.
     * @param json the JSON array as a String where each object can map to the specified class.
     * @throws RealmException if mapping from JSON fails.
     */
    public <E extends RealmModel> void createAllFromJson(Class<E> clazz, String json) {
        if (clazz == null || json == null || json.length() == 0) {
            return;
        }

        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (JSONException e) {
            throw new RealmException("Could not create JSON array from string", e);
        }

        createAllFromJson(clazz, arr);
    }

    /**
     * Tries to update a list of existing objects identified by their primary key with new JSON data. If an existing
     * object could not be found in the Realm, a new object will be created. This must happen within a transaction.
     * If updating a {@link RealmObject} and a field is not found in the JSON object, that field will not be updated.
     * If a new {@link RealmObject} is created and a field is not found in the JSON object, that field will be assigned
     * the default value for the field type.
     *
     * @param clazz type of {@link io.realm.RealmObject} to create or update. It must have a primary key defined.
     * @param json string with an array of JSON objects.
     * @throws java.lang.IllegalArgumentException if trying to update a class without a
     *         {@link io.realm.annotations.PrimaryKey}.
     * @throws RealmException if unable to create a JSON array from the json string.
     * @see #createAllFromJson(Class, String)
     */
    public <E extends RealmModel> void createOrUpdateAllFromJson(Class<E> clazz, String json) {
        if (clazz == null || json == null || json.length() == 0) {
            return;
        }
        checkHasPrimaryKey(clazz);

        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (JSONException e) {
            throw new RealmException("Could not create JSON array from string", e);
        }

        createOrUpdateAllFromJson(clazz, arr);
    }

    /**
     * Creates a Realm object for each object in a JSON array. This must be done within a transaction.
     * JSON properties with {@code null} value will map to the default value for the data type in Realm and unknown properties
     * will be ignored. If a {@link RealmObject} field is not present in the JSON object the {@link RealmObject} field
     * will be set to the default value for that type.
     *
     * @param clazz type of Realm objects created.
     * @param inputStream the JSON array as a InputStream. All objects in the array must be of the specified class.
     * @throws RealmException if mapping from JSON fails.
     * @throws IOException if something was wrong with the input stream.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public <E extends RealmModel> void createAllFromJson(Class<E> clazz, InputStream inputStream) throws IOException {
        if (clazz == null || inputStream == null) {
            return;
        }

        JsonReader reader = new JsonReader(new InputStreamReader(inputStream, "UTF-8"));
        try {
            reader.beginArray();
            while (reader.hasNext()) {
                configuration.getSchemaMediator().createUsingJsonStream(clazz, this, reader);
            }
            reader.endArray();
        } finally {
            reader.close();
        }
    }

    /**
     * Tries to update a list of existing objects identified by their primary key with new JSON data. If an existing
     * object could not be found in the Realm, a new object will be created. This must happen within a transaction.
     * If updating a {@link RealmObject} and a field is not found in the JSON object, that field will not be updated.
     * If a new {@link RealmObject} is created and a field is not found in the JSON object, that field will be assigned
     * the default value for the field type.
     *
     * @param clazz type of {@link io.realm.RealmObject} to create or update. It must have a primary key defined.
     * @param in the InputStream with a list of object data in JSON format.
     * @throws java.lang.IllegalArgumentException if trying to update a class without a
     *         {@link io.realm.annotations.PrimaryKey}.
     * @throws RealmException if unable to read JSON.
     * @see #createOrUpdateAllFromJson(Class, java.io.InputStream)
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public <E extends RealmModel> void createOrUpdateAllFromJson(Class<E> clazz, InputStream in) throws IOException {
        if (clazz == null || in == null) {
            return;
        }
        checkHasPrimaryKey(clazz);

        // As we need the primary key value we have to first parse the entire input stream as in the general
        // case that value might be the last property :(
        Scanner scanner = null;
        try {
            scanner = getFullStringScanner(in);
            JSONArray json = new JSONArray(scanner.next());
            for (int i = 0; i < json.length(); i++) {
                configuration.getSchemaMediator().createOrUpdateUsingJsonObject(clazz, this, json.getJSONObject(i), true);
            }
        } catch (JSONException e) {
            throw new RealmException("Failed to read JSON", e);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }
    }

    /**
     * Creates a Realm object pre-filled with data from a JSON object. This must be done inside a transaction. JSON
     * properties with {@code null} values will map to the default value for the data type in Realm and unknown properties will
     * be ignored. If a {@link RealmObject} field is not present in the JSON object the {@link RealmObject} field will
     * be set to the default value for that type.
     *
     * @param clazz type of Realm object to create.
     * @param json the JSONObject with object data.
     * @return created object or {@code null} if no JSON data was provided.
     * @throws RealmException if the mapping from JSON fails.
     * @see #createOrUpdateObjectFromJson(Class, org.json.JSONObject)
     */
    public <E extends RealmModel> E createObjectFromJson(Class<E> clazz, JSONObject json) {
        if (clazz == null || json == null) {
            return null;
        }

        try {
            return configuration.getSchemaMediator().createOrUpdateUsingJsonObject(clazz, this, json, false);
        } catch (JSONException e) {
            throw new RealmException("Could not map JSON", e);
        }
    }

    /**
     * Tries to update an existing object defined by its primary key with new JSON data. If no existing object could be
     * found a new object will be saved in the Realm. This must happen within a transaction. If updating a {@link RealmObject}
     * and a field is not found in the JSON object, that field will not be updated. If a new {@link RealmObject} is
     * created and a field is not found in the JSON object, that field will be assigned the default value for the field type.
     *
     * @param clazz Type of {@link io.realm.RealmObject} to create or update. It must have a primary key defined.
     * @param json {@link org.json.JSONObject} with object data.
     * @return created or updated {@link io.realm.RealmObject}.
     * @throws java.lang.IllegalArgumentException if trying to update a class without a
     *         {@link io.realm.annotations.PrimaryKey}.
     * @throws RealmException if JSON data cannot be mapped.
     * @see #createObjectFromJson(Class, org.json.JSONObject)
     */
    public <E extends RealmModel> E createOrUpdateObjectFromJson(Class<E> clazz, JSONObject json) {
        if (clazz == null || json == null) {
            return null;
        }
        checkHasPrimaryKey(clazz);
        try {
            E realmObject = configuration.getSchemaMediator().createOrUpdateUsingJsonObject(clazz, this, json, true);
            return realmObject;
        } catch (JSONException e) {
            throw new RealmException("Could not map JSON", e);
        }
    }

    /**
     * Creates a Realm object pre-filled with data from a JSON object. This must be done inside a transaction. JSON
     * properties with {@code null} values will map to the default value for the data type in Realm and unknown properties will
     * be ignored. If a {@link RealmObject} field is not present in the JSON object the {@link RealmObject} field will
     * be set to the default value for that type.
     *
     * @param clazz type of Realm object to create.
     * @param json the JSON string with object data.
     * @return created object or {@code null} if JSON string was empty or null.
     * @throws RealmException if mapping to json failed.
     */
    public <E extends RealmModel> E createObjectFromJson(Class<E> clazz, String json) {
        if (clazz == null || json == null || json.length() == 0) {
            return null;
        }

        JSONObject obj;
        try {
            obj = new JSONObject(json);
        } catch (JSONException e) {
            throw new RealmException("Could not create Json object from string", e);
        }

        return createObjectFromJson(clazz, obj);
    }

    /**
     * Tries to update an existing object defined by its primary key with new JSON data. If no existing object could be
     * found a new object will be saved in the Realm. This must happen within a transaction. If updating a
     * {@link RealmObject} and a field is not found in the JSON object, that field will not be updated. If a new
     * {@link RealmObject} is created and a field is not found in the JSON object, that field will be assigned the
     * default value for the field type.
     *
     * @param clazz type of {@link io.realm.RealmObject} to create or update. It must have a primary key defined.
     * @param json string with object data in JSON format.
     * @return created or updated {@link io.realm.RealmObject}.
     * @throws java.lang.IllegalArgumentException if trying to update a class without a
     *         {@link io.realm.annotations.PrimaryKey}.
     * @throws RealmException if JSON object cannot be mapped from the string parameter.
     * @see #createObjectFromJson(Class, String)
     */
    public <E extends RealmModel> E createOrUpdateObjectFromJson(Class<E> clazz, String json) {
        if (clazz == null || json == null || json.length() == 0) {
            return null;
        }
        checkHasPrimaryKey(clazz);

        JSONObject obj;
        try {
            obj = new JSONObject(json);
        } catch (JSONException e) {
            throw new RealmException("Could not create Json object from string", e);
        }

        return createOrUpdateObjectFromJson(clazz, obj);
    }

    /**
     * Creates a Realm object pre-filled with data from a JSON object. This must be done inside a transaction. JSON
     * properties with {@code null} value will map to the default value for the data type in Realm and unknown properties will
     * be ignored. If a {@link RealmObject} field is not present in the JSON object the {@link RealmObject} field will
     * be set to the default value for that type.
     *
     * @param clazz type of Realm object to create.
     * @param inputStream the JSON object data as a InputStream.
     * @return created object or {@code null} if JSON string was empty or null.
     * @throws RealmException if the mapping from JSON failed.
     * @throws IOException if something went wrong with the input stream.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public <E extends RealmModel> E createObjectFromJson(Class<E> clazz, InputStream inputStream) throws IOException {
        if (clazz == null || inputStream == null) {
            return null;
        }
        E realmObject;
        Table table = getTable(clazz);
        if (table.hasPrimaryKey()) {
            // As we need the primary key value we have to first parse the entire input stream as in the general
            // case that value might be the last property :(
            Scanner scanner = null;
            try {
                scanner = getFullStringScanner(inputStream);
                JSONObject json = new JSONObject(scanner.next());
                realmObject = configuration.getSchemaMediator().createOrUpdateUsingJsonObject(clazz, this, json, false);

            } catch (JSONException e) {
                throw new RealmException("Failed to read JSON", e);
            } finally {
                if (scanner != null) {
                    scanner.close();
                }
            }
        } else {
            JsonReader reader = new JsonReader(new InputStreamReader(inputStream, "UTF-8"));
            try {
                realmObject = configuration.getSchemaMediator().createUsingJsonStream(clazz, this, reader);
            } finally {
                reader.close();
            }
        }
        return realmObject;
    }

    /**
     * Tries to update an existing object defined by its primary key with new JSON data. If no existing object could be
     * found a new object will be saved in the Realm. This must happen within a transaction. If updating a
     * {@link RealmObject} and a field is not found in the JSON object, that field will not be updated. If a new
     * {@link RealmObject} is created and a field is not found in the JSON object, that field will be assigned the
     * default value for the field type.
     *
     * @param clazz type of {@link io.realm.RealmObject} to create or update. It must have a primary key defined.
     * @param in the {@link InputStream} with object data in JSON format.
     * @return created or updated {@link io.realm.RealmObject}.
     * @throws java.lang.IllegalArgumentException if trying to update a class without a
     *         {@link io.realm.annotations.PrimaryKey}.
     * @throws RealmException if failure to read JSON.
     * @see #createObjectFromJson(Class, java.io.InputStream)
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public <E extends RealmModel> E createOrUpdateObjectFromJson(Class<E> clazz, InputStream in) throws IOException {
        if (clazz == null || in == null) {
            return null;
        }
        checkHasPrimaryKey(clazz);

        // As we need the primary key value we have to first parse the entire input stream as in the general
        // case that value might be the last property :(
        Scanner scanner = null;
        try {
            scanner = getFullStringScanner(in);
            JSONObject json = new JSONObject(scanner.next());
            return createOrUpdateObjectFromJson(clazz, json);
        } catch (JSONException e) {
            throw new RealmException("Failed to read JSON", e);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }
    }

    private Scanner getFullStringScanner(InputStream in) {
        return new Scanner(in, "UTF-8").useDelimiter("\\A");
    }

    /**
     * Instantiates and adds a new object to the Realm.
     *
     * @param clazz the Class of the object to create.
     * @return the new object.
     * @throws RealmException if an object cannot be created.
     */
    public <E extends RealmModel> E createObject(Class<E> clazz) {
        checkIfValid();
        Table table = getTable(clazz);
        long rowIndex = table.addEmptyRow();
        return get(clazz, rowIndex);
    }

    /**
     * Instantiates and adds a new object to the Realm with the primary key value already set.
     * <p>
     * If the value violates the primary key constraint, no object will be added and a {@link RealmException} will be
     * thrown.
     *
     * @param clazz the Class of the object to create.
     * @param primaryKeyValue value for the primary key field.
     * @return the new object.
     * @throws RealmException if object could not be created due to the primary key being invalid.
     * @throws IllegalStateException if the model clazz does not have an primary key defined.
     * @throws IllegalArgumentException if the {@code primaryKeyValue} doesn't have a value that can be converted to the
     *                                  expected value.
     */
    public <E extends RealmModel> E createObject(Class<E> clazz, Object primaryKeyValue) {
        Table table = getTable(clazz);
        long rowIndex = table.addEmptyRowWithPrimaryKey(primaryKeyValue);
        return get(clazz, rowIndex);
    }

    /**
     * Copies a RealmObject to the Realm instance and returns the copy. Any further changes to the original RealmObject
     * will not be reflected in the Realm copy. This is a deep copy, so all referenced objects will be copied. Objects
     * already in this Realm will be ignored.
     * <p>
     * Please note, copying an object will copy all field values. Any unset field in this and child objects will be
     * set to their default value if not provided.
     *
     * @param object the {@link io.realm.RealmObject} to copy to the Realm.
     * @return a managed RealmObject with its properties backed by the Realm.
     * @throws java.lang.IllegalArgumentException if the object is {@code null} or it belongs to a Realm instance
     * in a different thread.
     */
    public <E extends RealmModel> E copyToRealm(E object) {
        checkNotNullObject(object);
        return copyOrUpdate(object, false, new HashMap<RealmModel, RealmObjectProxy>());
    }

    /**
     * Updates an existing RealmObject that is identified by the same {@link io.realm.annotations.PrimaryKey} or creates
     * a new copy if no existing object could be found. This is a deep copy or update i.e., all referenced objects will be
     * either copied or updated.
     * <p>
     * Please note, copying an object will copy all field values. Any unset field in the object and child objects will be
     * set to their default value if not provided.
     *
     * @param object {@link io.realm.RealmObject} to copy or update.
     * @return the new or updated RealmObject with all its properties backed by the Realm.
     * @throws java.lang.IllegalArgumentException if the object is {@code null} or doesn't have a Primary key defined
     *  or it belongs to a Realm instance in a different thread.
     * @see #copyToRealm(RealmModel)
     */
    public <E extends RealmModel> E copyToRealmOrUpdate(E object) {
        checkNotNullObject(object);
        checkHasPrimaryKey(object.getClass());
        return copyOrUpdate(object, true, new HashMap<RealmModel, RealmObjectProxy>());
    }

    /**
     * Copies a collection of RealmObjects to the Realm instance and returns their copy. Any further changes to the
     * original RealmObjects will not be reflected in the Realm copies. This is a deep copy i.e., all referenced objects
     * will be copied. Objects already in this Realm will be ignored.
     * <p>
     * Please note, copying an object will copy all field values. Any unset field in the objects and child objects will be
     * set to their default value if not provided.
     *
     * @param objects the RealmObjects to copy to the Realm.
     * @return a list of the the converted RealmObjects that all has their properties managed by the Realm.
     * @throws io.realm.exceptions.RealmException if any of the objects has already been added to Realm.
     * @throws java.lang.IllegalArgumentException if any of the elements in the input collection is {@code null}.
     */
    public <E extends RealmModel> List<E> copyToRealm(Iterable<E> objects) {
        if (objects == null) {
            return new ArrayList<E>();
        }
        Map<RealmModel, RealmObjectProxy> cache = new HashMap<RealmModel, RealmObjectProxy>();
        ArrayList<E> realmObjects = new ArrayList<E>();
        for (E object : objects) {
            checkNotNullObject(object);
            realmObjects.add(copyOrUpdate(object, false, cache));
        }

        return realmObjects;
    }

    /**
     * Insert a list of an unmanaged RealmObjects. This is generally faster than {@link #copyToRealm(Iterable)} since it
     * doesn't return the inserted elements, and performs minimum allocations and checks.
     * After being inserted any changes to the original objects will not be persisted.
     * <p>
     * Please note:
     * <ul>
     * <li>
     *     We don't check if the provided objects are already managed or not, so inserting a managed object might duplicate it.
     *     Duplication will only happen if the object doesn't have a primary key. Objects with primary keys will never get duplicated.
     * </li>
     * <li>We don't create (nor return) a managed {@link RealmObject} for each element</li>
     * <li>Copying an object will copy all field values. Any unset field in the object and child objects will be set to their default value if not provided</li>
     * </ul>
     * <p>
     * If you want these checks and the managed {@link RealmObject} returned, use {@link #copyToRealm(Iterable)}, otherwise if
     * you have a large number of object this method is generally faster.
     *
     * @param objects RealmObjects to insert.
     * @throws IllegalStateException if the corresponding Realm is closed, called from an incorrect thread or not in a
     * transaction.
     * @see #copyToRealm(Iterable)
     */
    public void insert(Collection<? extends RealmModel> objects) {
        checkIfValidAndInTransaction();
        if (objects == null) {
            throw new IllegalArgumentException("Null objects cannot be inserted into Realm.");
        }
        if (objects.isEmpty()) {
            return;
        }
        configuration.getSchemaMediator().insert(this, objects);
    }

    /**
     * Insert an unmanaged RealmObject. This is generally faster than {@link #copyToRealm(RealmModel)} since it
     * doesn't return the inserted elements, and performs minimum allocations and checks.
     * After being inserted any changes to the original object will not be persisted.
     * <p>
     * Please note:
     * <ul>
     * <li>
     *     We don't check if the provided objects are already managed or not, so inserting a managed object might duplicate it.
     *     Duplication will only happen if the object doesn't have a primary key. Objects with primary keys will never get duplicated.
     * </li>
     * <li>We don't create (nor return) a managed {@link RealmObject} for each element</li>
     * <li>Copying an object will copy all field values. Any unset field in the object and child objects will be set to their default value if not provided</li>
     * </ul>
     * <p>
     * If you want these checks and the managed {@link RealmObject} returned, use {@link #copyToRealm(RealmModel)}, otherwise if
     * you have a large number of object this method is generally faster.
     *
     * @param object RealmObjects to insert.
     * @throws IllegalStateException if the corresponding Realm is closed, called from an incorrect thread or not in a
     * transaction.
     * @throws io.realm.exceptions.RealmPrimaryKeyConstraintException if two objects with the same primary key is
     * inserted or if a primary key value already exists in the Realm.
     * @see #copyToRealm(RealmModel)
     */
    public void insert(RealmModel object) {
        checkIfValidAndInTransaction();
        if (object == null) {
            throw new IllegalArgumentException("Null object cannot be inserted into Realm.");
        }
        Map<RealmModel, Long> cache = new IdentityHashMap<RealmModel, Long>();
        configuration.getSchemaMediator().insert(this, object, cache);
    }

    /**
     * Insert or update a list of unmanaged RealmObjects. This is generally faster than {@link #copyToRealmOrUpdate(Iterable)} since it
     * doesn't return the inserted elements, and performs minimum allocations and checks.
     * After being inserted any changes to the original objects will not be persisted.
     * <p>
     * Please note:
     * <ul>
     * <li>
     *     We don't check if the provided objects are already managed or not, so inserting a managed object might duplicate it.
     *     Duplication will only happen if the object doesn't have a primary key. Objects with primary keys will never get duplicated.
     * </li>
     * <li>We don't create (nor return) a managed {@link RealmObject} for each element</li>
     * <li>Copying an object will copy all field values. Any unset field in the object and child objects will be set to their default value if not provided</li>
     * </ul>
     * <p>
     * If you want these checks and the managed {@link RealmObject} returned, use {@link #copyToRealm(Iterable)}, otherwise if
     * you have a large number of object this method is generally faster.
     *
     * @param objects RealmObjects to insert.
     * @throws IllegalStateException if the corresponding Realm is closed, called from an incorrect thread or not in a
     * transaction.
     * @throws io.realm.exceptions.RealmPrimaryKeyConstraintException if two objects with the same primary key is
     * inserted or if a primary key value already exists in the Realm.
     *
     * @see #copyToRealmOrUpdate(Iterable)
     */
    public void insertOrUpdate(Collection<? extends RealmModel> objects) {
        checkIfValidAndInTransaction();
        if (objects == null) {
            throw new IllegalArgumentException("Null objects cannot be inserted into Realm.");
        }
        if (objects.isEmpty()) {
            return;
        }
        configuration.getSchemaMediator().insertOrUpdate(this, objects);
    }

    /**
     * Insert or update an unmanaged RealmObject. This is generally faster than {@link #copyToRealmOrUpdate(RealmModel)} since it
     * doesn't return the inserted elements, and performs minimum allocations and checks.
     * After being inserted any changes to the original object will not be persisted.
     * <p>
     * Please note:
     * <ul>
     * <li>
     *     We don't check if the provided objects are already managed or not, so inserting a managed object might duplicate it.
     *     Duplication will only happen if the object doesn't have a primary key. Objects with primary keys will never get duplicated.
     * </li>
     * <li>We don't create (nor return) a managed {@link RealmObject} for each element</li>
     * <li>Copying an object will copy all field values. Any unset field in the object and child objects will be set to their default value if not provided</li>
     * </ul>
     * <p>
     * If you want these checks and the managed {@link RealmObject} returned, use {@link #copyToRealm(RealmModel)}, otherwise if
     * you have a large number of object this method is generally faster.
     *
     * @param object RealmObjects to insert.
     * @throws IllegalStateException if the corresponding Realm is closed, called from an incorrect thread or not in a
     * transaction.
     * @see #copyToRealmOrUpdate(RealmModel)
     */
    public void insertOrUpdate(RealmModel object) {
        checkIfValidAndInTransaction();
        if (object == null) {
            throw new IllegalArgumentException("Null object cannot be inserted into Realm.");
        }
        Map<RealmModel, Long> cache = new IdentityHashMap<RealmModel, Long>();
        configuration.getSchemaMediator().insertOrUpdate(this, object, cache);
    }

    /**
     * Updates a list of existing RealmObjects that is identified by their {@link io.realm.annotations.PrimaryKey} or
     * creates a new copy if no existing object could be found. This is a deep copy or update i.e., all referenced objects
     * will be either copied or updated.
     * <p>
     * Please note, copying an object will copy all field values. Any unset field in the objects and child objects will be
     * set to their default value if not provided.
     *
     * @param objects a list of objects to update or copy into Realm.
     * @return a list of all the new or updated RealmObjects.
     * @throws java.lang.IllegalArgumentException if RealmObject is {@code null} or doesn't have a Primary key defined.
     * @see #copyToRealm(Iterable)
     */
    public <E extends RealmModel> List<E> copyToRealmOrUpdate(Iterable<E> objects) {
        if (objects == null) {
            return new ArrayList<E>(0);
        }

        Map<RealmModel, RealmObjectProxy> cache = new HashMap<RealmModel, RealmObjectProxy>();
        ArrayList<E> realmObjects = new ArrayList<E>();
        for (E object : objects) {
            checkNotNullObject(object);
            realmObjects.add(copyOrUpdate(object, true, cache));
        }

        return realmObjects;
    }

    /**
     * Makes an unmanaged in-memory copy of already persisted RealmObjects. This is a deep copy that will copy all
     * referenced objects.
     * <p>
     * The copied objects are all detached from Realm and they will no longer be automatically updated. This means
     * that the copied objects might contain data that are no longer consistent with other managed Realm objects.
     * <p>
     * *WARNING*: Any changes to copied objects can be merged back into Realm using {@link #copyToRealmOrUpdate(RealmModel)},
     * but all fields will be overridden, not just those that were changed. This includes references to other objects,
     * and can potentially override changes made by other threads.
     *
     * @param realmObjects RealmObjects to copy.
     * @param <E> type of object.
     * @return an in-memory detached copy of managed RealmObjects.
     * @throws IllegalArgumentException if the RealmObject is no longer accessible or it is a {@link DynamicRealmObject}.
     * @see #copyToRealmOrUpdate(Iterable)
     */
    public <E extends RealmModel> List<E> copyFromRealm(Iterable<E> realmObjects) {
        return copyFromRealm(realmObjects, Integer.MAX_VALUE);
    }

    /**
     * Makes an unmanaged in-memory copy of already persisted RealmObjects. This is a deep copy that will copy all
     * referenced objects up to the defined depth.
     * <p>
     * The copied objects are all detached from Realm and they will no longer be automatically updated. This means
     * that the copied objects might contain data that are no longer consistent with other managed Realm objects.
     * <p>
     * *WARNING*: Any changes to copied objects can be merged back into Realm using {@link #copyToRealmOrUpdate(Iterable)},
     * but all fields will be overridden, not just those that were changed. This includes references to other objects
     * even though they might be {@code null} due to {@code maxDepth} being reached. This can also potentially override
     * changes made by other threads.
     *
     * @param realmObjects RealmObjects to copy.
     * @param maxDepth limit of the deep copy. All references after this depth will be {@code null}. Starting depth is
     *                 {@code 0}.
     * @param <E> type of object.
     * @return an in-memory detached copy of the RealmObjects.
     * @throws IllegalArgumentException if {@code maxDepth < 0}, the RealmObject is no longer accessible or it is a
     *         {@link DynamicRealmObject}.
     * @see #copyToRealmOrUpdate(Iterable)
     */
    public <E extends RealmModel> List<E> copyFromRealm(Iterable<E> realmObjects, int maxDepth) {
        checkMaxDepth(maxDepth);
        if (realmObjects == null) {
            return new ArrayList<E>(0);
        }

        ArrayList<E> unmanagedObjects = new ArrayList<E>();
        Map<RealmModel, RealmObjectProxy.CacheData<RealmModel>> listCache = new HashMap<RealmModel, RealmObjectProxy.CacheData<RealmModel>>();
        for (E object : realmObjects) {
            checkValidObjectForDetach(object);
            unmanagedObjects.add(createDetachedCopy(object, maxDepth, listCache));
        }

        return unmanagedObjects;
    }

    /**
     * Makes an unmanaged in-memory copy of an already persisted {@link RealmObject}. This is a deep copy that will copy
     * all referenced objects.
     * <p>
     * The copied object(s) are all detached from Realm and they will no longer be automatically updated. This means
     * that the copied objects might contain data that are no longer consistent with other managed Realm objects.
     * <p>
     * *WARNING*: Any changes to copied objects can be merged back into Realm using
     * {@link #copyToRealmOrUpdate(RealmModel)}, but all fields will be overridden, not just those that were changed.
     * This includes references to other objects, and can potentially override changes made by other threads.
     *
     * @param realmObject {@link RealmObject} to copy.
     * @param <E> type of object.
     * @return an in-memory detached copy of the managed {@link RealmObject}.
     * @throws IllegalArgumentException if the RealmObject is no longer accessible or it is a {@link DynamicRealmObject}.
     * @see #copyToRealmOrUpdate(RealmModel)
     */
    public <E extends RealmModel> E copyFromRealm(E realmObject) {
        return copyFromRealm(realmObject, Integer.MAX_VALUE);
    }

    /**
     * Makes an unmanaged in-memory copy of an already persisted {@link RealmObject}. This is a deep copy that will copy
     * all referenced objects up to the defined depth.
     * <p>
     * The copied object(s) are all detached from Realm and they will no longer be automatically updated. This means
     * that the copied objects might contain data that are no longer consistent with other managed Realm objects.
     * <p>
     * *WARNING*: Any changes to copied objects can be merged back into Realm using
     * {@link #copyToRealmOrUpdate(RealmModel)}, but all fields will be overridden, not just those that were changed.
     * This includes references to other objects even though they might be {@code null} due to {@code maxDepth} being
     * reached. This can also potentially override changes made by other threads.
     *
     * @param realmObject {@link RealmObject} to copy.
     * @param maxDepth limit of the deep copy. All references after this depth will be {@code null}. Starting depth is
     * {@code 0}.
     * @param <E> type of object.
     * @return an in-memory detached copy of the managed {@link RealmObject}.
     * @throws IllegalArgumentException if {@code maxDepth < 0}, the RealmObject is no longer accessible or it is a
     *         {@link DynamicRealmObject}.
     * @see #copyToRealmOrUpdate(RealmModel)
     */
    public <E extends RealmModel> E copyFromRealm(E realmObject, int maxDepth) {
        checkMaxDepth(maxDepth);
        checkValidObjectForDetach(realmObject);
        return createDetachedCopy(realmObject, maxDepth, new HashMap<RealmModel, RealmObjectProxy.CacheData<RealmModel>>());
    }

    /**
     * Returns a typed RealmQuery, which can be used to query for specific objects of this type
     *
     * @param clazz the class of the object which is to be queried for.
     * @return a typed RealmQuery, which can be used to query for specific objects of this type.
     * @see io.realm.RealmQuery
     */
    public <E extends RealmModel> RealmQuery<E> where(Class<E> clazz) {
        checkIfValid();
        return RealmQuery.createQuery(this, clazz);
    }

    /**
     * Adds a change listener to the Realm.
     * <p>
     * The listeners will be executed on every loop of a Handler thread if
     * the current thread or other threads committed changes to the Realm.
     * <p>
     * Realm instances are per thread singletons and cached, so listeners should be
     * removed manually even if calling {@link #close()}. Otherwise there is a
     * risk of memory leaks.
     *
     * @param listener the change listener.
     * @throws IllegalArgumentException if the change listener is {@code null}.
     * @throws IllegalStateException if you try to register a listener from a non-Looper or {@link IntentService} thread.
     * @see io.realm.RealmChangeListener
     * @see #removeChangeListener(RealmChangeListener)
     * @see #removeAllChangeListeners()
     */
    public void addChangeListener(RealmChangeListener<Realm> listener) {
        super.addListener(listener);
    }

    /**
     * Executes a given transaction on the Realm. {@link #beginTransaction()} and {@link #commitTransaction()} will be
     * called automatically. If any exception is thrown during the transaction {@link #cancelTransaction()} will be
     * called instead of {@link #commitTransaction()}.
     *
     * @param transaction the {@link io.realm.Realm.Transaction} to execute.
     * @throws IllegalArgumentException if the {@code transaction} is {@code null}.
     */
    public void executeTransaction(Transaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction should not be null");
        }

        beginTransaction();
        try {
            transaction.execute(this);
            commitTransaction();
        } catch (Throwable e) {
            if (isInTransaction()) {
                cancelTransaction();
            } else {
                RealmLog.w("Could not cancel transaction, not currently in a transaction.");
            }
            throw e;
        }
    }

    /**
     * Similar to {@link #executeTransaction(Transaction)} but runs asynchronously on a worker thread.
     *
     * @param transaction {@link io.realm.Realm.Transaction} to execute.
     * @return a {@link RealmAsyncTask} representing a cancellable task.
     * @throws IllegalArgumentException if the {@code transaction} is {@code null}, or if the Realm is opened from
     *                                  another thread.
     */
    public RealmAsyncTask executeTransactionAsync(final Transaction transaction) {
        return executeTransactionAsync(transaction, null, null);
    }

    /**
     * Similar to {@link #executeTransactionAsync(Transaction)}, but also accepts an OnSuccess callback.
     *
     * @param transaction {@link io.realm.Realm.Transaction} to execute.
     * @param onSuccess callback invoked when the transaction succeeds.
     * @return a {@link RealmAsyncTask} representing a cancellable task.
     * @throws IllegalArgumentException if the {@code transaction} is {@code null}, or if the realm is opened from
     *                                  another thread.
     */
    public RealmAsyncTask executeTransactionAsync(final Transaction transaction, final Realm.Transaction.OnSuccess onSuccess) {
        if (onSuccess == null) {
            throw new IllegalArgumentException("onSuccess callback can't be null");
        }

        return executeTransactionAsync(transaction, onSuccess, null);
    }

    /**
     * Similar to {@link #executeTransactionAsync(Transaction)}, but also accepts an OnError callback.
     *
     * @param transaction {@link io.realm.Realm.Transaction} to execute.
     * @param onError callback invoked when the transaction fails.
     * @return a {@link RealmAsyncTask} representing a cancellable task.
     * @throws IllegalArgumentException if the {@code transaction} is {@code null}, or if the realm is opened from
     *                                  another thread.
     */
    public RealmAsyncTask executeTransactionAsync(final Transaction transaction, final Realm.Transaction.OnError onError) {
        if (onError == null) {
            throw new IllegalArgumentException("onError callback can't be null");
        }

        return executeTransactionAsync(transaction, null, onError);
    }

    /**
     * Similar to {@link #executeTransactionAsync(Transaction)}, but also accepts an OnSuccess and OnError callbacks.
     *
     * @param transaction {@link io.realm.Realm.Transaction} to execute.
     * @param onSuccess callback invoked when the transaction succeeds.
     * @param onError callback invoked when the transaction fails.
     * @return a {@link RealmAsyncTask} representing a cancellable task.
     * @throws IllegalArgumentException if the {@code transaction} is {@code null}, or if the realm is opened from
     *                                  another thread.
     */
    public RealmAsyncTask executeTransactionAsync(final Transaction transaction, final Realm.Transaction.OnSuccess onSuccess, final Realm.Transaction.OnError onError) {
        checkIfValid();

        if (transaction == null) {
            throw new IllegalArgumentException("Transaction should not be null");
        }

        // If the user provided a Callback then we make sure, the current Realm has a Handler
        // we can use to deliver the result
        if ((onSuccess != null || onError != null)  && handler == null) {
            throw new IllegalStateException("Your Realm is opened from a thread without a Looper" +
                    " and you provided a callback, we need a Handler to invoke your callback");
        }

        // We need to use the same configuration to open a background SharedRealm (i.e Realm)
        // to perform the transaction
        final RealmConfiguration realmConfiguration = getConfiguration();

        final Future<?> pendingTransaction = asyncTaskExecutor.submitTransaction(new Runnable() {
            @Override
            public void run() {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }

                boolean transactionCommitted = false;
                final Throwable[] exception = new Throwable[1];
                final Realm bgRealm = Realm.getInstance(realmConfiguration);
                bgRealm.beginTransaction();
                try {
                    transaction.execute(bgRealm);

                    if (!Thread.currentThread().isInterrupted()) {
                        bgRealm.commitAsyncTransaction(new Runnable() {
                            @Override
                            public void run() {
                                // The bgRealm needs to be closed before post event to caller's handler to avoid
                                // concurrency problem. eg.: User wants to delete Realm in the callbacks.
                                // This will close Realm before sending REALM_CHANGED.
                                bgRealm.close();
                            }
                        });
                        transactionCommitted = true;
                    }
                } catch (final Throwable e) {
                    exception[0] = e;
                } finally {
                    if (!bgRealm.isClosed()) {
                        if (bgRealm.isInTransaction()) {
                            bgRealm.cancelTransaction();
                        } else if (exception[0] != null) {
                            RealmLog.w("Could not cancel transaction, not currently in a transaction.");
                        }
                        bgRealm.close();
                    }

                    final Throwable backgroundException = exception[0];
                    // Send response as the final step to ensure the bg thread quit before others get the response!
                    if (handler != null
                            && !Thread.currentThread().isInterrupted()
                            && handler.getLooper().getThread().isAlive()) {

                        if (transactionCommitted) {
                            // This will be treated like a special REALM_CHANGED event
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    handlerController.handleAsyncTransactionCompleted(onSuccess != null ? new Runnable() {
                                        @Override
                                        public void run() {
                                            onSuccess.onSuccess();
                                        }
                                    } : null);
                                }
                            });
                        }

                        // Send errors directly to the looper, so they don't get intercepted by the HandlerController.
                        if (backgroundException != null) {
                            if (onError != null) {
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        onError.onError(backgroundException);
                                    }
                                });
                            } else {
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (backgroundException instanceof RuntimeException) {
                                            throw (RuntimeException) backgroundException;
                                        } else if (backgroundException instanceof Exception) {
                                            throw new RealmException("Async transaction failed", backgroundException);
                                        } else if (backgroundException instanceof Error) {
                                            throw (Error) backgroundException;
                                        }
                                    }
                                });
                            }
                        }

                    } else {
                        // Throw exception in the worker thread if the caller thread terminated
                        if (backgroundException != null) {
                            if (backgroundException instanceof RuntimeException) {
                                //noinspection ThrowFromFinallyBlock
                                throw (RuntimeException) backgroundException;
                            } else if (backgroundException instanceof Exception) {
                                //noinspection ThrowFromFinallyBlock
                                throw new RealmException("Async transaction failed", backgroundException);
                            } else if (backgroundException instanceof Error) {
                                //noinspection ThrowFromFinallyBlock
                                throw (Error) backgroundException;
                            }
                        }
                    }
                }
            }
        });

        return new RealmAsyncTask(pendingTransaction);
    }

    /**
     * Deletes all objects of the specified class from the Realm.
     *
     * @param clazz the class which objects should be removed.
     * @throws IllegalStateException if the corresponding Realm is closed or called from an incorrect thread.
     */
    public void delete(Class<? extends RealmModel> clazz) {
        checkIfValid();
        getTable(clazz).clear();
    }


    @SuppressWarnings("unchecked")
    private <E extends RealmModel> E copyOrUpdate(E object, boolean update, Map<RealmModel, RealmObjectProxy> cache) {
        checkIfValid();
        return configuration.getSchemaMediator().copyOrUpdate(this, object, update, cache);
    }

    private <E extends RealmModel> E createDetachedCopy(E object, int maxDepth, Map<RealmModel, RealmObjectProxy.CacheData<RealmModel>> cache) {
        checkIfValid();
        return configuration.getSchemaMediator().createDetachedCopy(object, maxDepth, cache);
    }

    private <E extends RealmModel> void checkNotNullObject(E object) {
        if (object == null) {
            throw new IllegalArgumentException("Null objects cannot be copied into Realm.");
        }
    }

    private void checkHasPrimaryKey(Class<? extends RealmModel> clazz) {
        if (!getTable(clazz).hasPrimaryKey()) {
            throw new IllegalArgumentException("A RealmObject with no @PrimaryKey cannot be updated: " + clazz.toString());
        }
    }

    private void checkMaxDepth(int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be > 0. It was: " + maxDepth);
        }
    }

    private <E extends RealmModel> void checkValidObjectForDetach(E realmObject) {
        if (realmObject == null) {
            throw new IllegalArgumentException("Null objects cannot be copied from Realm.");
        }
        if (!RealmObject.isValid(realmObject)) {
            throw new IllegalArgumentException("RealmObject is not valid, so it cannot be copied.");
        }
        if (realmObject instanceof DynamicRealmObject) {
            throw new IllegalArgumentException("DynamicRealmObject cannot be copied from Realm.");
        }
    }

    /**
     * Manually triggers the migration associated with a given RealmConfiguration. If Realm is already at the latest
     * version, nothing will happen.
     *
     * @param configuration {@link RealmConfiguration}
     * @throws FileNotFoundException if the Realm file doesn't exist.
     */
    public static void migrateRealm(RealmConfiguration configuration) throws FileNotFoundException {
        migrateRealm(configuration, null);
    }

    /**
     * Manually triggers a migration on a RealmMigration.
     *
     * @param configuration the{@link RealmConfiguration}.
     * @param migration the {@link RealmMigration} to run on the Realm. This will override any migration set on the
     *                  configuration.
     * @throws FileNotFoundException if the Realm file doesn't exist.
     */
    public static void migrateRealm(RealmConfiguration configuration, RealmMigration migration)
            throws FileNotFoundException {
        BaseRealm.migrateRealm(configuration, migration, new MigrationCallback() {
            @Override
            public void migrationComplete() {
            }
        });
    }

    /**
     * Deletes the Realm file specified by the given {@link RealmConfiguration} from the filesystem.
     * All Realm instances must be closed before calling this method.
     *
     * @param configuration a {@link RealmConfiguration}.
     * @return {@code false} if a file could not be deleted. The failing file will be logged.
     */
    public static boolean deleteRealm(RealmConfiguration configuration) {
        return BaseRealm.deleteRealm(configuration);
    }

    /**
     * Compacts a Realm file. A Realm file usually contain free/unused space.
     * This method removes this free space and the file size is thereby reduced.
     * Objects within the Realm files are untouched.
     * <p>
     * The file must be closed before this method is called, otherwise {@code false} will be returned.<br>
     * The file system should have free space for at least a copy of the Realm file.<br>
     * The Realm file is left untouched if any file operation fails.<br>
     *
     * @param configuration a {@link RealmConfiguration} pointing to a Realm file.
     * @return {@code true} if successful, {@code false} if any file operation failed.
     * @throws IllegalArgumentException if the realm file is encrypted. Compacting an encrypted Realm file is not
     *                                  supported yet.
     */
    public static boolean compactRealm(RealmConfiguration configuration) {
        return BaseRealm.compactRealm(configuration);
    }

    // Get the canonical path for a given file
    static String getCanonicalPath(File realmFile) {
        try {
            return realmFile.getCanonicalPath();
        } catch (IOException e) {
            throw new RealmIOException("Could not resolve the canonical path to the Realm file: " + realmFile.getAbsolutePath());
        }
    }

    Table getTable(Class<? extends RealmModel> clazz) {
        Table table = classToTable.get(clazz);
        if (table == null) {
            clazz = Util.getOriginalModelClass(clazz);
            //table = sharedGroupManager.getTable(configuration.getSchemaMediator().getTableName(clazz));
            table = sharedRealm.getTable(configuration.getSchemaMediator().getTableName(clazz));
            classToTable.put(clazz, table);
        }
        return table;
    }

    /**
     * Returns the default Realm module. This module contains all Realm classes in the current project, but not those
     * from library or project dependencies. Realm classes in these should be exposed using their own module.
     *
     * @return the default Realm module or {@code null} if no default module exists.
     * @throws RealmException if unable to create an instance of the module.
     * @see io.realm.RealmConfiguration.Builder#modules(Object, Object...)
     */
    public static Object getDefaultModule() {
        String moduleName = "io.realm.DefaultRealmModule";
        Class<?> clazz;
        //noinspection TryWithIdenticalCatches
        try {
            clazz = Class.forName(moduleName);
            Constructor<?> constructor = clazz.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ClassNotFoundException e) {
            return null;
        } catch (InvocationTargetException e) {
            throw new RealmException("Could not create an instance of " + moduleName, e);
        } catch (InstantiationException e) {
            throw new RealmException("Could not create an instance of " + moduleName, e);
        } catch (IllegalAccessException e) {
            throw new RealmException("Could not create an instance of " + moduleName, e);
        }
    }

    /**
     * Encapsulates a Realm transaction.
     * <p>
     * Using this class will automatically handle {@link #beginTransaction()} and {@link #commitTransaction()}
     * If any exception is thrown during the transaction {@link #cancelTransaction()} will be called instead of
     * {@link #commitTransaction()}.
     */
    public interface Transaction {
        void execute(Realm realm);

        /**
         * Callback invoked to notify the caller thread.
         */
        class Callback {
            public void onSuccess() {}
            public void onError(Exception e) {}
        }

        /**
         * Callback invoked to notify the caller thread about the success of the transaction.
         */
        interface OnSuccess {
            void onSuccess();
        }

        /**
         * Callback invoked to notify the caller thread about error during the transaction.
         * The transaction will be rolled back and the background Realm will be closed before
         * invoking {@link #onError(Throwable)}.
         */
        interface OnError {
            void onError(Throwable error);
        }
    }
}
