package com.moez.QKSMS.feature.compose

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.moez.QKSMS.R
import kotlinx.android.synthetic.main.activity_date_time_picker.am_pm_picker
import kotlinx.android.synthetic.main.activity_date_time_picker.cancel_btn
import kotlinx.android.synthetic.main.activity_date_time_picker.date_ll
import kotlinx.android.synthetic.main.activity_date_time_picker.date_picker
import kotlinx.android.synthetic.main.activity_date_time_picker.hour_picker
import kotlinx.android.synthetic.main.activity_date_time_picker.minute_picker
import kotlinx.android.synthetic.main.activity_date_time_picker.month_picker
import kotlinx.android.synthetic.main.activity_date_time_picker.ok_btn
import kotlinx.android.synthetic.main.activity_date_time_picker.time_ll
import kotlinx.android.synthetic.main.activity_date_time_picker.year_picker
import java.util.Calendar

class DateTimePickerActivity : AppCompatActivity() {


    private var isDatePickerShowing: Boolean = true

    private lateinit var calendar: Calendar

    private var selectedDate: Int = -1
    private var selectedMonth: Int = -1
    private var selectedYear: Int = -1

    private var selectedHour: Int = -1
    private var selectedMinute: Int = -1

    private var is24HourFormat: Boolean = false

    private var amPm: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_date_time_picker)

        isDatePickerShowing = true

        setDate()
        initClicks()

    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                ok_btn.performClick()
                return true
            }
        } else if (event.keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                cancel_btn.performClick()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }


    private fun setDate() {
        calendar = Calendar.getInstance()

        val currentYear = calendar.get(Calendar.YEAR)
        val minYear = 1900
        val maxYear = 2100

        val currentMonth = calendar.get(Calendar.MONTH)

        val currentDate = calendar.get(Calendar.DAY_OF_MONTH)

        date_picker.setOnValueChangedListener { _, _, newVal ->
            selectedDate = newVal
        }



        month_picker.setOnValueChangedListener { _, _, newVal ->
            selectedMonth = newVal
            date_picker.maxValue = getMaxDaysInMonth(newVal, year_picker.value)
        }

        year_picker.setOnValueChangedListener { _, _, newVal ->
            selectedYear = newVal
            date_picker.maxValue = getMaxDaysInMonth(month_picker.value, newVal)
        }


        year_picker.minValue = minYear
        year_picker.maxValue = maxYear
        year_picker.value = currentYear
        selectedYear = currentYear


        val months = arrayOf(
            "Jan",
            "Feb",
            "Mar",
            "Apr",
            "May",
            "Jun",
            "Jul",
            "Aug",
            "Sep",
            "Oct",
            "Nov",
            "Dec"
        )
        month_picker.displayedValues = months
        month_picker.minValue = 0
        month_picker.maxValue = 11
        month_picker.value = currentMonth
        selectedMonth = currentMonth


        val maxDays = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        date_picker.minValue = 1
        date_picker.maxValue = maxDays
        date_picker.value = currentDate
        selectedDate = currentDate
    }


    private fun getMaxDaysInMonth(month: Int, year: Int): Int {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.MONTH, month)
        calendar.set(Calendar.YEAR, year)
        return calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    }


    private fun initClicks() {
        cancel_btn.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        ok_btn.setOnClickListener {
            if (isDatePickerShowing) {
                showTimePicker()
            } else {
                val mills = getTimeInMillis()
                val data = Intent()
                data.putExtra(TIME_IN_MILLS, mills)
                setResult(Activity.RESULT_OK, data)
                finish()
            }
        }

    }

    private fun showTimePicker() {
        isDatePickerShowing = false
        date_ll.visibility = View.GONE
        time_ll.visibility = View.VISIBLE

        setTime()
    }

    private fun setTime() {
        val calendar = Calendar.getInstance()
        is24HourFormat = DateFormat.is24HourFormat(this)

        am_pm_picker.visibility = if (is24HourFormat) View.GONE else View.VISIBLE

        hour_picker.setOnValueChangedListener { _, _, newVal ->
            selectedHour = newVal
        }

        minute_picker.setOnValueChangedListener { _, _, newVal ->
            selectedMinute = newVal
        }

        am_pm_picker.setOnValueChangedListener { _, _, newVal ->
            amPm = newVal
        }



        if (is24HourFormat) {
            amPm = -1
            val currentHour24 = calendar.get(Calendar.HOUR_OF_DAY)
            hour_picker.minValue = 0
            hour_picker.maxValue = 23
            hour_picker.value = currentHour24
            selectedHour = currentHour24
            hour_picker.displayedValues = null
        } else {
            val currentHour12 = calendar.get(Calendar.HOUR)
            val currentAmPm = calendar.get(Calendar.AM_PM)
            hour_picker.minValue = 1
            hour_picker.maxValue = 12
            hour_picker.value = currentHour12
            selectedHour = currentHour12

            val amPmDisplayValues = arrayOf("AM", "PM")
            am_pm_picker.minValue = 0
            am_pm_picker.maxValue = 1
            am_pm_picker.displayedValues = amPmDisplayValues
            am_pm_picker.value = currentAmPm
            amPm = currentAmPm
        }

        minute_picker.minValue = 0
        minute_picker.maxValue = 59

        val currentMinute = calendar.get(Calendar.MINUTE)
        minute_picker.value = currentMinute
        selectedMinute = currentMinute
    }

    private fun getTimeInMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(selectedYear, selectedMonth, selectedDate)

        if (is24HourFormat) {
            calendar.set(Calendar.HOUR_OF_DAY, selectedHour)
        } else {
            val calendarHour = if (amPm == 0) selectedHour else selectedHour + 12
            calendar.set(Calendar.HOUR, calendarHour)
        }

        calendar.set(Calendar.MINUTE, selectedMinute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        return calendar.timeInMillis
    }

    companion object {
        private const val TAG = "DateTimePickerActivity"
        const val TIME_IN_MILLS = "time_in_mills"
    }

}