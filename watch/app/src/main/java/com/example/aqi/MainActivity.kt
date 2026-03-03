package com.example.aqi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.*
import com.example.aqi.data.AqiViewModel
import com.example.aqi.data.SensorData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Map an AQI value to the triangle drawable resource (0-5 = Good to Hazardous). */
private fun aqiTriangleRes(aqi: Int): Int = when {
    aqi <= 50 -> R.drawable.aqi_triangle_0
    aqi <= 100 -> R.drawable.aqi_triangle_1
    aqi <= 150 -> R.drawable.aqi_triangle_2
    aqi <= 200 -> R.drawable.aqi_triangle_3
    aqi <= 300 -> R.drawable.aqi_triangle_4
    else -> R.drawable.aqi_triangle_5
}

/** Format UTC epoch seconds as a local time string, e.g. "2:00 PM" or "Yesterday 2:00 PM". */
private fun formatTimestamp(epochSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val zonedTime = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val timeStr = zonedTime.format(DateTimeFormatter.ofPattern("h:mm a"))
    val sensorDate = zonedTime.toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        sensorDate == today -> timeStr
        sensorDate == today.minusDays(1) -> "Yesterday $timeStr"
        else -> "${zonedTime.format(DateTimeFormatter.ofPattern("M/d"))} $timeStr"
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: AqiViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            viewModel.forceRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }

        setContent {
            MaterialTheme {
                AqiApp(viewModel)
            }
        }
    }
}

enum class Page { DETAILS, SETTINGS, ABOUT }

@Composable
fun AqiApp(viewModel: AqiViewModel) {
    val pages = Page.entries
    val pagerState = rememberPagerState { pages.size }
    val latestData by viewModel.latestData.collectAsState()
    val cacheMinutes by viewModel.locationCacheMinutes.collectAsState()

    val pageIndicatorState = remember {
        object : PageIndicatorState {
            override val pageCount: Int get() = pages.size
            override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
            override val selectedPage: Int get() = pagerState.currentPage
        }
    }

    Scaffold(
        pageIndicator = {
            HorizontalPageIndicator(pageIndicatorState = pageIndicatorState)
        }
    ) {
        HorizontalPager(state = pagerState) { pageIndex ->
            when (pages[pageIndex]) {
                Page.DETAILS -> DetailsPage(
                    data = latestData,
                    onRefresh = { viewModel.forceRefresh() }
                )
                Page.SETTINGS -> SettingsPage(
                    cacheMinutes = cacheMinutes,
                    onCacheMinutesChanged = { viewModel.setLocationCacheMinutes(it) }
                )
                Page.ABOUT -> AboutPage()
            }
        }
    }
}

@Composable
fun DetailsPage(
    data: SensorData?,
    onRefresh: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AQI Details", style = MaterialTheme.typography.title2)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (data != null) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(data.name, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.secondary, textAlign = TextAlign.Center)
                    Text("Updated: ${formatTimestamp(data.timestamp)}", style = MaterialTheme.typography.caption3)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            data.metrics.forEach { (metricName, value) ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(metricName, style = MaterialTheme.typography.body1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(value.toString(), style = MaterialTheme.typography.body1, color = MaterialTheme.colors.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Image(
                                painter = painterResource(aqiTriangleRes(value)),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text("No data available.", style = MaterialTheme.typography.caption1)
            }
        }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(16.dp))
                Chip(
                    onClick = onRefresh,
                    label = { Text("Refresh", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    modifier = Modifier.wrapContentWidth()
                )
            }
        }
    }
}

@Composable
fun AboutPage() {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("About", style = MaterialTheme.typography.title2)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "EPA AQI Watch Face Complication.\nData sourced from AirNow.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption1
                )
            }
        }
    }
}

@Composable
fun SettingsPage(
    cacheMinutes: Int,
    onCacheMinutesChanged: (Int) -> Unit
) {
    val options = listOf(15, 30, 60, 120, 240)

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Location Cache", style = MaterialTheme.typography.title3)
                Text("Max Age", style = MaterialTheme.typography.caption2)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        options.forEach { mins ->
            item {
                val hours = mins / 60
                val label = if (mins >= 60) "$hours hr${if (hours > 1) "s" else ""}" else "$mins min"
                Chip(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    onClick = { onCacheMinutesChanged(mins) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (mins == cacheMinutes) MaterialTheme.colors.primary else MaterialTheme.colors.surface
                    ),
                    label = {
                        Text(
                            text = label,
                            color = if (mins == cacheMinutes) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface
                        )
                    }
                )
            }
        }
    }
}
