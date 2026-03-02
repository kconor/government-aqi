export interface Env {
  AQI_BUCKET: R2Bucket;
}

export default {
  // Handle HTTP requests (serving the JSON files from R2)
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    const headers = new Headers();
    headers.set('Access-Control-Allow-Origin', '*');
    headers.set('Content-Type', 'application/json');

    try {
      if (path === '/api/aqi') {
        const object = await env.AQI_BUCKET.get('all_data.json');
        if (!object) {
          return new Response(JSON.stringify({ error: 'Data not found' }), { status: 404, headers });
        }
        
        // Cloudflare R2 automatically handles conditional requests and ETag headers
        // Just return the object body directly
        return new Response(object.body, { 
            headers: {
                ...Object.fromEntries(headers),
                'Cache-Control': 'public, max-age=1800' // Hint to clients they can cache this for 30 mins
            } 
        });
      }

      return new Response(JSON.stringify({ error: 'Not found' }), { status: 404, headers });
    } catch (e: any) {
      return new Response(JSON.stringify({ error: 'Internal server error', details: e.message }), { status: 500, headers });
    }
  },

  // Handle Scheduled Events (fetching from EPA and storing in R2)
  async scheduled(event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(updateAqiData(env));
  }
};

// Timezone abbreviation → UTC offset in hours
const TZ_OFFSETS: Record<string, number> = {
  EST: -5, CST: -6, MST: -7, PST: -8, AKT: -9, HST: -10,
  CET: +1, EAT: +3, IST: +5.5, NPT: +5.75, TJT: +5, GST: +4,
};

/** Parse EPA date/time/tz into UTC epoch seconds. Returns 0 on failure. */
function toEpochSeconds(validDate: string, validTime: string, timeZone: string): number {
  // validDate = "MM/DD/YY", validTime = "HH:MM" or empty, timeZone = "EST" etc.
  const parts = validDate.split('/');
  if (parts.length !== 3) return 0;

  const month = parseInt(parts[0], 10);
  const day = parseInt(parts[1], 10);
  let year = parseInt(parts[2], 10);
  if (isNaN(month) || isNaN(day) || isNaN(year)) return 0;

  // Two-digit year: 00-99 → 2000-2099
  if (year < 100) year += 2000;

  let hours = 0, minutes = 0;
  if (validTime && validTime.trim()) {
    const tp = validTime.trim().split(':');
    hours = parseInt(tp[0], 10) || 0;
    minutes = parseInt(tp[1], 10) || 0;
  }

  const offsetHours = TZ_OFFSETS[timeZone.trim()] ?? 0;

  // Build a UTC date by subtracting the timezone offset
  // Date.UTC returns milliseconds, we convert to seconds
  const utcMs = Date.UTC(year, month - 1, day, hours, minutes, 0, 0);
  const epochSeconds = Math.floor(utcMs / 1000) - Math.floor(offsetHours * 3600);
  return epochSeconds;
}

async function updateAqiData(env: Env) {
  console.log("Starting AQI data update...");

  try {
    const response = await fetch("https://files.airnowtech.org/airnow/today/reportingarea.dat");
    if (!response.ok) {
      throw new Error(`Failed to fetch reportingarea.dat: ${response.status} ${response.statusText}`);
    }

    const textData = await response.text();
    const lines = textData.split('\n');

    // We use a Map to group by city-state name
    const locationsMap = new Map<string, any>();
    let latestTimestamp = 0;

    for (const line of lines) {
      if (!line.trim()) continue;

      const parts = line.split('|');
      if (parts.length < 16) continue;

      const [
        issueDate, validDate, validTime, timeZone,
        recordSequence, dataType, primary,
        reportingArea, stateCode, latStr, lonStr,
        parameterName, aqiValueStr, aqiCategory
      ] = parts;

      // We only care about current Observations
      if (dataType !== 'O') continue;

      const lat = parseFloat(latStr);
      const lon = parseFloat(lonStr);
      const aqiValue = parseInt(aqiValueStr, 10);

      if (isNaN(lat) || isNaN(lon) || isNaN(aqiValue)) continue;

      // Use the formatted name as the unique key, saving space by not having a separate ID field
      const name = stateCode.trim() ? `${reportingArea.trim()}, ${stateCode.trim()}` : reportingArea.trim();

      const epochSeconds = toEpochSeconds(validDate, validTime, timeZone);

      if (!locationsMap.has(name)) {
        locationsMap.set(name, {
          n: name,
          la: lat,
          lo: lon,
          m: {},
          t: epochSeconds
        });
      }

      const sensor = locationsMap.get(name)!;

      // Update sensor timestamp to the latest observation for this location
      if (epochSeconds > sensor.t) {
          sensor.t = epochSeconds;
      }

      // Store metric (e.g., OZONE, PM2.5)
      const metricKey = parameterName.trim().toUpperCase();
      sensor.m[metricKey] = aqiValue;

      // If this is the "Primary" parameter, set it as the top-level 'A' (AQI)
      // and capture the category string (Good, Moderate, etc.)
      if (primary === 'Y') {
        sensor.A = aqiValue;
        sensor.C = aqiCategory.trim();
      }

      // Keep track of the global latest observation time
      if (epochSeconds > latestTimestamp) {
          latestTimestamp = epochSeconds;
      }
    }

    const finalData = {
        t: latestTimestamp, // Global timestamp (UTC epoch seconds)
        s: Array.from(locationsMap.values())
    };
    
    const jsonData = JSON.stringify(finalData);
    
    // Write ONE single file to R2
    await env.AQI_BUCKET.put('all_data.json', jsonData, {
      httpMetadata: { contentType: 'application/json' }
    });
    
    console.log(`Saved all_data.json with ${finalData.s.length} sensors. Total size: ${jsonData.length} bytes.`);

  } catch (err: any) {
    console.error("Error updating AQI data:", err.message);
  }
}
