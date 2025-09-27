package com.example.cellfinder

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.*

class MapFilterDialog(
    private val context: Context,
    private val currentFilter: MapFilter,
    private val availableCellTypes: List<String>,
    private val onFilterChanged: (MapFilter) -> Unit
) {
    
    fun show() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_map_filter, null)
        
        // Time window radio buttons
        val timeWindowGroup = dialogView.findViewById<RadioGroup>(R.id.timeWindowGroup)
        val radio15min = dialogView.findViewById<RadioButton>(R.id.radio15min)
        val radio1hour = dialogView.findViewById<RadioButton>(R.id.radio1hour)
        val radio6hours = dialogView.findViewById<RadioButton>(R.id.radio6hours)
        val radio24hours = dialogView.findViewById<RadioButton>(R.id.radio24hours)
        
        // Set current time window selection
        when (currentFilter.timeWindowMinutes) {
            15 -> radio15min.isChecked = true
            60 -> radio1hour.isChecked = true
            360 -> radio6hours.isChecked = true
            1440 -> radio24hours.isChecked = true
            else -> radio1hour.isChecked = true
        }
        
        // RSSI filter controls
        val enableRssiFilter = dialogView.findViewById<CheckBox>(R.id.enableRssiFilter)
        val rssiRangeLayout = dialogView.findViewById<LinearLayout>(R.id.rssiRangeLayout)
        val minRssiInput = dialogView.findViewById<EditText>(R.id.minRssiInput)
        val maxRssiInput = dialogView.findViewById<EditText>(R.id.maxRssiInput)
        
        enableRssiFilter.isChecked = currentFilter.minRssi != null || currentFilter.maxRssi != null
        rssiRangeLayout.isEnabled = enableRssiFilter.isChecked
        
        currentFilter.minRssi?.let { minRssiInput.setText(it.toString()) }
        currentFilter.maxRssi?.let { maxRssiInput.setText(it.toString()) }
        
        enableRssiFilter.setOnCheckedChangeListener { _, isChecked ->
            rssiRangeLayout.isEnabled = isChecked
            for (i in 0 until rssiRangeLayout.childCount) {
                rssiRangeLayout.getChildAt(i).isEnabled = isChecked
            }
        }
        
        // Display options
        val showCellObservations = dialogView.findViewById<CheckBox>(R.id.showCellObservations)
        val showBaseStations = dialogView.findViewById<CheckBox>(R.id.showBaseStations)
        val showDebugCircles = dialogView.findViewById<CheckBox>(R.id.showDebugCircles)
        
        showCellObservations.isChecked = currentFilter.showCellObservations
        showBaseStations.isChecked = currentFilter.showBaseStations
        showDebugCircles.isChecked = currentFilter.showDebugCircles
        
        // Cell types filter
        val enableCellTypeFilter = dialogView.findViewById<CheckBox>(R.id.enableCellTypeFilter)
        val cellTypesLayout = dialogView.findViewById<LinearLayout>(R.id.cellTypesLayout)
        
        enableCellTypeFilter.isChecked = currentFilter.cellTypes != null
        cellTypesLayout.isEnabled = enableCellTypeFilter.isChecked
        
        // Add checkboxes for each available cell type
        val cellTypeCheckboxes = mutableMapOf<String, CheckBox>()
        for (cellType in availableCellTypes) {
            val checkbox = CheckBox(context)
            checkbox.text = cellType
            checkbox.isChecked = currentFilter.cellTypes?.contains(cellType) ?: true
            checkbox.isEnabled = enableCellTypeFilter.isChecked
            cellTypesLayout.addView(checkbox)
            cellTypeCheckboxes[cellType] = checkbox
        }
        
        enableCellTypeFilter.setOnCheckedChangeListener { _, isChecked ->
            cellTypesLayout.isEnabled = isChecked
            for (i in 0 until cellTypesLayout.childCount) {
                cellTypesLayout.getChildAt(i).isEnabled = isChecked
            }
        }
        
        // Create and show dialog
        AlertDialog.Builder(context)
            .setTitle("Map Filters")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                val newFilter = buildFilterFromDialog(
                    timeWindowGroup,
                    enableRssiFilter,
                    minRssiInput,
                    maxRssiInput,
                    showCellObservations,
                    showBaseStations,
                    showDebugCircles,
                    enableCellTypeFilter,
                    cellTypeCheckboxes
                )
                onFilterChanged(newFilter)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                onFilterChanged(MapFilter.DEFAULT)
            }
            .show()
    }
    
    private fun buildFilterFromDialog(
        timeWindowGroup: RadioGroup,
        enableRssiFilter: CheckBox,
        minRssiInput: EditText,
        maxRssiInput: EditText,
        showCellObservations: CheckBox,
        showBaseStations: CheckBox,
        showDebugCircles: CheckBox,
        enableCellTypeFilter: CheckBox,
        cellTypeCheckboxes: Map<String, CheckBox>
    ): MapFilter {
        
        // Get time window
        val timeWindowMinutes = when (timeWindowGroup.checkedRadioButtonId) {
            R.id.radio15min -> 15
            R.id.radio1hour -> 60
            R.id.radio6hours -> 360
            R.id.radio24hours -> 1440
            else -> 60
        }
        
        // Get RSSI range
        val minRssi = if (enableRssiFilter.isChecked) {
            val text = minRssiInput.text.toString()
            if (text.isNotBlank()) text.toIntOrNull() else null
        } else null
        
        val maxRssi = if (enableRssiFilter.isChecked) {
            val text = maxRssiInput.text.toString()
            if (text.isNotBlank()) text.toIntOrNull() else null
        } else null
        
        // Get selected cell types
        val cellTypes = if (enableCellTypeFilter.isChecked) {
            cellTypeCheckboxes.entries
                .filter { it.value.isChecked }
                .map { it.key }
                .toSet()
        } else null
        
        return MapFilter(
            timeWindowMinutes = timeWindowMinutes,
            cellTypes = cellTypes,
            minRssi = minRssi,
            maxRssi = maxRssi,
            showCellObservations = showCellObservations.isChecked,
            showBaseStations = showBaseStations.isChecked,
            showDebugCircles = showDebugCircles.isChecked
        )
    }
}