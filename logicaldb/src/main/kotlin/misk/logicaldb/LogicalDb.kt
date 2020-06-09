package misk.logicaldb

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBDeleteExpression
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig.ConsistentReads
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig.ConsistentReads.EVENTUAL
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBSaveExpression
import misk.logicaldb.internal.LogicalDbFactory
import javax.annotation.CheckReturnValue
import kotlin.reflect.KClass

/**
 * A collection of tables that implement the DynamoDB best practice of putting multiple
 * item types into the same storage table. This makes it possible to perform aggregate operations
 * and transactions on those item types.
 */
interface LogicalDb : LogicalTable.Factory {

  /**
   * Retrieves multiple items from multiple tables using their primary keys.
   */
  fun batchLoad(
    keys: KeySet,
    consistentReads: ConsistentReads = EVENTUAL
  ): ItemSet

  fun batchLoad(keys: Iterable<Any>, consistentReads: ConsistentReads = EVENTUAL): ItemSet {
    return batchLoad(KeySet(keys), consistentReads)
  }

  fun batchLoad(vararg keys: Any, consistentReads: ConsistentReads = EVENTUAL): ItemSet {
    return batchLoad(keys.toList(), consistentReads)
  }

  /**
   * Saves and deletes the objects given using one or more calls to the
   * [AmazonDynamoDB.batchWriteItem] API. **Callers should always check the returned
   * [BatchWriteResult]** because this method returns normally even if some writes were not
   * performed.
   *
   * This method does not support versioning annotations and behaves as if
   * [DynamoDBMapperConfig.SaveBehavior.CLOBBER] was specified.
   *
   * A single call to BatchWriteItem can write up to 16 MB of data, which can comprise as many as 25
   * put or delete requests. Individual items to be written can be as large as 400 KB.
   *
   * In order to improve performance with these large-scale operations, this does not behave
   * in the same way as individual PutItem and DeleteItem calls would. For example, you cannot specify
   * conditions on individual put and delete requests, and BatchWriteItem does not return deleted
   * items in the response.
   */
  @CheckReturnValue
  fun batchWrite(writeSet: BatchWriteSet): BatchWriteResult

  /**
   * Transactionally loads objects specified by transactionLoadRequest by calling
   * [AmazonDynamoDB.transactGetItems] API.
   *
   * A transaction cannot contain more than 25 unique items.
   * A transaction cannot contain more than 4 MB of data.
   * No two actions in a transaction can work against the same item in the same table.
   */
  fun transactionLoad(keys: KeySet): ItemSet

  fun transactionLoad(keys: Iterable<Any>): ItemSet {
    return transactionLoad(KeySet(keys))
  }

  fun transactionLoad(vararg keys: Any): ItemSet {
    return transactionLoad(keys.toList())
  }

  /**
   * Transactionally writes objects specified by transactionWriteRequest by calling
   * [AmazonDynamoDB.transactWriteItems] API.
   *
   * This method does not support versioning annotations. It throws
   * [com.amazonaws.SdkClientException] exception if class of any input object is annotated
   * with [DynamoDBVersionAttribute] or [DynamoDBVersioned].
   *
   * A transaction cannot contain more than 25 unique items, including conditions.
   * A transaction cannot contain more than 4 MB of data.
   * No two actions in a transaction can work against the same item in the same table.
   * For example, you cannot both ConditionCheck and Update the same item in one transaction.
   */
  fun transactionWrite(writeSet: TransactionWriteSet)

  companion object {
    inline operator fun <reified DB : LogicalDb> invoke(
      dynamoDbMapper: DynamoDBMapper,
      config: DynamoDBMapperConfig = DynamoDBMapperConfig.DEFAULT
    ): DB {
      return create(DB::class, dynamoDbMapper, config)
    }

    fun <DB : LogicalDb> create(
      dbType: KClass<DB>,
      dynamoDbMapper: DynamoDBMapper,
      config: DynamoDBMapperConfig = DynamoDBMapperConfig.DEFAULT
    ): DB {
      return LogicalDbFactory(dynamoDbMapper, config).logicalDb(dbType)
    }
  }
}

/**
 * A collection of views on a DynamoDB table that makes it easy to model heterogeneous items
 * using strongly typed data classes.
 */
interface LogicalTable<RI : Any> :
    Scannable<RI, RI>,
    View<RI, RI>,
    InlineView.Factory,
    SecondaryIndex.Factory {

  interface Factory {

    fun <T : LogicalTable<RI>, RI : Any> logicalTable(
      tableType: KClass<T>
    ): T
  }
}

interface View<K : Any, I : Any> {
  /**
   * Returns an item whose keys match those of the prototype key object given, or null if no
   * such item exists.
   */
  fun load(key: K, consistentReads: ConsistentReads = EVENTUAL): I?

  /**
   * Saves an item in DynamoDB. This method uses [DynamoDBMapperConfig.SaveBehavior.PUT] to clear
   * and replace all attributes, including unmodeled ones, on save. Partial update, i.e.
   * [DynamoDBMapperConfig.SaveBehavior.UPDATE_SKIP_NULL_ATTRIBUTES], is not supported yet.
   *
   * Any options specified in the [saveExpression] parameter will be overlaid on any constraints due
   * to versioned attributes.
   *
   * If [ignoreVersionConstraints] is true, version attributes will be discarded.
   */
  fun save(
    item: I,
    saveExpression: DynamoDBSaveExpression? = null,
    ignoreVersionConstraints: Boolean = false
  )

  /**
   * Deletes the item identified by [key] from its DynamoDB table using [deleteExpression]. Any
   * options specified in the [deleteExpression] parameter will be overlaid on any constraints due
   * to versioned attributes.
   *
   * If the item to be deleted has versioned attributes, load the item and use [delete] instead or
   * use [ignoreVersionConstraints] to discard them.
   */
  fun deleteKey(
    key: K,
    deleteExpression: DynamoDBDeleteExpression? = null,
    ignoreVersionConstraints: Boolean = false
  )

  /**
   * Deletes [item] from its DynamoDB table using [deleteExpression]. Any options specified in the
   * [deleteExpression] parameter will be overlaid on any constraints due to versioned attributes.
   *
   * If [ignoreVersionConstraints] is true, version attributes will not be considered when deleting
   * the object.
   */
  fun delete(
    item: I,
    deleteExpression: DynamoDBDeleteExpression? = null,
    ignoreVersionConstraints: Boolean = false
  )
}

interface Queryable<K : Any, I : Any> {
  /** Reads up to the [pageSize] items or a maximum of 1 MB of data. */
  fun query(
    startInclusive: K,
    endExclusive: K,
    consistentRead: Boolean = false,
    asc: Boolean = true,
    pageSize: Int = 100,
    initialOffset: Offset<K>? = null
  ): Page<K, I>
}

interface Scannable<K : Any, I : Any> {
  fun scan(): Page<K, I> = TODO("")
  fun parallelScan(threads: Int): Page<K, I> = TODO("")
  fun count(): Int = TODO("")
}

interface InlineView<K : Any, I : Any> : View<K, I>, Queryable<K, I> {

  interface Factory {
    fun <K : Any, I : Any> inlineView(
      keyType: KClass<K>,
      itemType: KClass<I>
    ): InlineView<K, I>
  }
}

interface SecondaryIndex<K : Any, I : Any> : Scannable<K, I>, Queryable<K, I> {

  interface Factory {
    fun <K : Any, I : Any> secondaryIndex(
      keyType: KClass<K>,
      itemType: KClass<I>
    ): SecondaryIndex<K, I>
  }
}