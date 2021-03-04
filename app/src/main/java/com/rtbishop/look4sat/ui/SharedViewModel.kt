/*******************************************************************************
 Look4Sat. Amateur radio satellite tracker and pass predictor.
 Copyright (C) 2019, 2020 Arty Bishop (bishop.arty@gmail.com)

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along
 with this program; if not, write to the Free Software Foundation, Inc.,
 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 ******************************************************************************/

package com.rtbishop.look4sat.ui

import android.net.Uri
import androidx.lifecycle.*
import com.github.amsacode.predict4java.SatelliteFactory
import com.rtbishop.look4sat.data.*
import com.rtbishop.look4sat.repository.SatelliteRepo
import com.rtbishop.look4sat.repository.PrefsRepo
import com.rtbishop.look4sat.utility.getPredictor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@HiltViewModel
class SharedViewModel @Inject constructor(
    private val prefsRepo: PrefsRepo,
    private val satelliteRepo: SatelliteRepo,
) : ViewModel() {
    
    private val _passes = MutableLiveData<Result<MutableList<SatPass>>>()
    private val _appEvent = MutableLiveData<Event<Int>>()
    private var selectedEntries = emptyList<SatEntry>()
    private var shouldTriggerCalculation = true
    
    init {
        if (prefsRepo.isFirstLaunch()) {
            updateDefaultSourcesAndEntries()
            prefsRepo.setFirstLaunchDone()
        }
    }
    
    fun getAppEvent(): LiveData<Event<Int>> = _appEvent
    fun getAppTimer() = liveData {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }
    
    fun getSources() = liveData { emit(prefsRepo.loadTleSources()) }
    fun getEntries() = satelliteRepo.getEntries().asLiveData()
    fun getPasses(): LiveData<Result<MutableList<SatPass>>> = _passes
    fun getTransmittersForSat(satId: Int) =
        satelliteRepo.getTransmittersForSat(satId).asLiveData()
    
    fun triggerCalculation() {
        if (shouldTriggerCalculation) {
            shouldTriggerCalculation = false
            calculatePasses()
        }
    }
    
    fun calculatePasses(dateNow: Date = Date(System.currentTimeMillis())) {
        shouldTriggerCalculation = false
        _passes.value = Result.InProgress
        viewModelScope.launch(Dispatchers.Default) {
            val passes = mutableListOf<SatPass>()
            selectedEntries.forEach { passes.addAll(getPasses(it, dateNow)) }
            val filteredPasses = sortList(passes, dateNow)
            _passes.postValue(Result.Success(filteredPasses))
        }
    }

    fun setEntries(entries: List<SatEntry>) {
        selectedEntries = entries.filter { it.isSelected }
    }

    fun updateEntriesFromFile(uri: Uri) {
        postAppEvent(Event(0))
        viewModelScope.launch {
            try {
                satelliteRepo.updateEntriesFromFile(uri)
            } catch (exception: Exception) {
                postAppEvent(Event(1))
            }
        }
    }

    fun updateEntriesFromSources(sources: List<TleSource>) {
        postAppEvent(Event(0))
        viewModelScope.launch {
            try {
                prefsRepo.saveTleSources(sources)
                satelliteRepo.updateEntriesFromWeb(sources)
                satelliteRepo.updateTransmitters()
            } catch (exception: Exception) {
                postAppEvent(Event(1))
            }
        }
    }

    fun updateEntriesSelection(entries: List<SatEntry>) {
        viewModelScope.launch {
            selectedEntries = entries.filter { it.isSelected }
            calculatePasses()
            satelliteRepo.updateEntriesSelection(selectedEntries.map { it.catNum })
        }
    }

    private fun postAppEvent(event: Event<Int>) {
        _appEvent.value = event
    }

    private fun getPasses(entry: SatEntry, dateNow: Date): MutableList<SatPass> {
        val gsp = prefsRepo.getStationPosition()
        val predictor = SatelliteFactory.createSatellite(entry.tle).getPredictor(gsp)
        val hoursAhead = prefsRepo.getHoursAhead()
        val passes = predictor.getPasses(dateNow, hoursAhead, true)
        val passList = passes.map { SatPass(entry.tle, predictor, it) }
        return passList as MutableList<SatPass>
    }

    private fun sortList(passes: MutableList<SatPass>, dateNow: Date): MutableList<SatPass> {
        val hoursAhead = prefsRepo.getHoursAhead()
        val dateFuture = Calendar.getInstance().apply {
            this.time = dateNow
            this.add(Calendar.HOUR, hoursAhead)
        }.time
        passes.removeAll { it.pass.startTime.after(dateFuture) }
        passes.removeAll { it.pass.endTime.before(dateNow) }
        passes.removeAll { it.pass.maxEl < prefsRepo.getMinElevation() }
        passes.sortBy { it.pass.startTime }
        return passes
    }

    private fun updateDefaultSourcesAndEntries() {
        updateEntriesFromSources(prefsRepo.loadTleSources())
    }
}
