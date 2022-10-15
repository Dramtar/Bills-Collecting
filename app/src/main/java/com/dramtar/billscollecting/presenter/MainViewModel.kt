package com.dramtar.billscollecting.presenter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dramtar.billscollecting.domain.BillData
import com.dramtar.billscollecting.domain.BillTypeData
import com.dramtar.billscollecting.domain.BillTypeGrouped
import com.dramtar.billscollecting.domain.Repository
import com.dramtar.billscollecting.utils.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: Repository
) : ViewModel() {
    var billListState by mutableStateOf(BillsState())
        private set
    var colorState by mutableStateOf(Color(0))
        private set
    private var billsJob: Job? = null

    private val updatingEvent = Channel<String>()
    val updatingEvents = updatingEvent.receiveAsFlow()

    init {
        selectDateRange()
        getBillTypes()
    }

    private fun overviewData() {
        billListState.bills?.let { bills ->
            val groupedBills = bills.groupBy { it.billTypeData }
            val listOfSum = groupedBills.mapValues { it.value.sumOf { it.amount } }.map {
                val percentage = (it.value / (billListState.totalSum)).toFloat()
                BillTypeGrouped(
                    type = it.key,
                    sumAmount = it.value,
                    formattedSumAmount = it.value.formatCurrency(),
                    percentage = percentage,
                    formattedPercentage = percentage.getFormattedPercentage()
                )
            }
            billListState = billListState.copy(
                overviewTypesList = listOfSum.sortedByDescending { it.percentage }
            )
        }
    }

    fun onAddBillTypeButtonClick() {
        val newRndColor = Color.getRndColor()
        billListState = billListState.copy(
            tmpBillType = BillTypeData(
                name = "",
                color = newRndColor,
                invertedColor = newRndColor.getOnColor()
            )
        )
    }

    fun onCompleteBillTypeButtonClick(name: String) {
        if (name.isBlank()) {
            clearTmpBillType()
            return
        }
        billListState.tmpBillType?.let { billType ->
            billListState = billListState.copy(
                tmpBillType = billType.copy(
                    name = name
                )
            )
            addBillType()
        }
    }

    private fun getGroupedByDateBillsList() {
        billListState =
            billListState.copy(gropedByDateBillsList = billListState.bills?.groupBy { it.date.getDayDayOfWeek() })
    }

    fun selectDateRange(date: Date = billListState.selectedDateRange) {
        val calender = Calendar.getInstance()
        calender.time = date
        billListState = billListState.copy(
            selectedDateRange = date
        )
        calender.set(Calendar.DAY_OF_MONTH, calender.getActualMinimum(Calendar.DAY_OF_MONTH))
        calender.set(Calendar.HOUR_OF_DAY, 0)
        val min = calender.timeInMillis
        calender.set(Calendar.HOUR_OF_DAY, 0)
        calender.set(Calendar.DAY_OF_MONTH, calender.getActualMaximum(Calendar.DAY_OF_MONTH))
        val max = calender.timeInMillis

        getBills(start = min, end = max)
    }

    fun onBillDeleteButtonClicked(data: BillData) {
        data.id?.let { id ->
            viewModelScope.launch { repository.deleteBill(id) }
        }
    }

    fun onBillTypeDeleteButtonClicked(data: BillTypeData) {
        viewModelScope.launch {
            repository.deleteBillType(data.id)
            if (billListState.selectedBillTypeId == data.id) {
                billListState = billListState.copy(selectedBillTypeId = "")
            }
        }
    }

    private fun setFirstTypeSelected() {
        if (billListState.selectedBillTypeId.isNotBlank()) return
        billListState.billTypes.apply {
            if (this.isEmpty()) return
            billListState = billListState.copy(
                selectedBillTypeId = this.first().id
            )
        }
    }

    private fun getBillTypes() {
        viewModelScope.launch {
            repository.getBillTypes().cancellable().collectLatest { typesList ->
                billListState = billListState.copy(
                    billTypes = typesList.sortedByDescending { it.priority }
                )
                setFirstTypeSelected()
            }
        }
    }

    private suspend fun increaseBillTypePriority(billTypeData: BillTypeData) {
        repository.updateBillType(billTypeData.copy(priority = billTypeData.priority.inc()))
    }

    private fun getBills(start: Long, end: Long) {
        billsJob?.cancel()
        billsJob = viewModelScope.launch {
            repository.getBills(start = start, end = end).collectLatest { billsList ->
                val sum = billsList.sumOf { it.amount }.roundToInt()
                billListState = billListState.copy(
                    bills = billsList,
                    totalSum = sum,
                    formattedTotalSum = sum.getFormattedLocalCurrency()
                )
                overviewData()
                getGroupedByDateBillsList()
            }
        }
    }

    fun billTypeSelected(id: String) {
        billListState = billListState.copy(selectedBillTypeId = id)
    }

    private fun addBillType() {
        billListState.tmpBillType?.let { billType ->
            viewModelScope.launch {
                val type = billType.copy(
                    id = billType.name.trim().replace(" ", "_").lowercase(),
                    name = billType.name,
                )
                repository.saveBillType(type)
                billTypeSelected(type.id)
                clearTmpBillType()
            }
        }
    }

    private fun clearTmpBillType() { //TODO NEED REWORK
        billListState = billListState.copy(tmpBillType = null)
    }

    fun getBillType(id: String) {
        viewModelScope.launch {
            repository.getBillTypeById(id = id)
        }
    }

    fun addBill(amount: Double, date: Long) {
        viewModelScope.launch {
            val bill = BillData(
                date = date,
                billTypeData = BillTypeData(id = billListState.selectedBillTypeId),
                amount = amount
            )
            repository.saveBill(billData = bill)
            val type = billListState.billTypes.find { it.id == billListState.selectedBillTypeId }
            type?.let {
                increaseBillTypePriority(it)
            }
        }
    }
}