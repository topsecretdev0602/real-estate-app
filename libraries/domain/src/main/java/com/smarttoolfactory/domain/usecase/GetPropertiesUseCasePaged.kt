package com.smarttoolfactory.domain.usecase

import com.smarttoolfactory.data.constant.ORDER_BY_NONE
import com.smarttoolfactory.data.model.local.PropertyEntity
import com.smarttoolfactory.data.repository.PropertyRepositoryCoroutines
import com.smarttoolfactory.domain.dispatcher.UseCaseDispatchers
import com.smarttoolfactory.domain.error.EmptyDataException
import com.smarttoolfactory.domain.mapper.PropertyEntityToItemListMapper
import com.smarttoolfactory.domain.model.PropertyItem
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * UseCase for getting UI Item list with offline first or offline last approach.
 *
 * * *Offline-first* first source to look for data is local data source, or database,
 * if database is empty or if caching is used it's expiry date is over, looks for remote source
 * for data. If both of the sources are empty, either return empty list or error to notify UI.
 *
 * This approach is good for when user is offline or have no internet connection, additional logic
 * can be added to check if user is offline to not deleted cached data.
 *
 * * *Offline-last* always checks remote data source for data and applies to database or offline
 * source as last resort. Offline-last is used especially when user refreshes data with a UI
 * element to get the latest data or new data is always first preference.
 */
class GetPropertiesUseCasePaged @Inject constructor(
    private val repository: PropertyRepositoryCoroutines,
    private val mapper: PropertyEntityToItemListMapper,
    private val dispatcherProvider: UseCaseDispatchers
) {

    /**
     * Function to retrieve data from repository with offline-last which checks
     * REMOTE data source first.
     *
     * * Check out Remote Source first
     * * If empty data or null returned throw empty set exception
     * * If error occurred while fetching data from remote: Try to fetch data from db
     * * If data is fetched from remote source: delete old data, save new data and return new data
     * * If both network and db don't have any data throw empty set exception
     *
     */
    fun getPagedOfflineLast(page: Int, orderBy: String): Flow<List<PropertyItem>> {
        return flow { emit(repository.fetchEntitiesFromRemote(orderBy)) }
            .map {
                if (it.isNullOrEmpty()) {
                    throw EmptyDataException("No Data is available in Remote source!")
                } else {
                    repository.run {

                        if (page == 1) {
                            deletePropertyEntities()
                        }

                        // 🔥 Add an insert order since we are not using Room's ORDER BY
                        it.forEachIndexed { index, propertyEntity ->
                            propertyEntity.insertOrder = index
                        }
                        savePropertyEntities(it)

                        getPropertyEntitiesFromLocal()
                    }
                }
            }
            .flowOn(dispatcherProvider.ioDispatcher)
            // This is where remote exception or least likely db exceptions are caught
            .catch { throwable ->
                emitAll(flowOf(repository.getPropertyEntitiesFromLocal()))
            }
            .map {
                toPropertyListOrError(it)
            }
    }

    private fun toPropertyListOrError(entityList: List<PropertyEntity>): List<PropertyItem> {
        return if (!entityList.isNullOrEmpty()) {
            mapper.map(entityList)
        } else {
            throw EmptyDataException("Empty data mapping error!")
        }
    }

    fun getCurrentSortKey(defaultKey: String = ORDER_BY_NONE): Flow<String> {
        return flow { emit(repository.getSortOrderKey()) }
            .catch {
                emitAll(flowOf(defaultKey))
            }
    }
}
