export interface Env {
  AQI_BUCKET: R2Bucket;
  API_SECRET: string;
}

async function hmacHex(secret: string, message: string): Promise<string> {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(message));
  return [...new Uint8Array(sig)].map(b => b.toString(16).padStart(2, '0')).join('');
}

async function verifyAuth(request: Request, secret: string): Promise<boolean> {
  const token = request.headers.get('X-Auth');
  if (!token) return false;
  const nowMinute = Math.floor(Date.now() / 60000);
  for (const m of [nowMinute, nowMinute - 1, nowMinute + 1]) {
    if (token === await hmacHex(secret, m.toString())) return true;
  }
  return false;
}

export default {
  // Handle HTTP requests — serve from edge cache, only hit R2 on cache miss
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const r2Key = url.pathname === '/api/aqi' ? 'all_data.json'
                : url.pathname === '/api/forecast' ? 'forecast_data.json'
                : null;

    if (!r2Key) {
      return new Response(JSON.stringify({ error: 'Not found' }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    if (!await verifyAuth(request, env.API_SECRET)) {
      return new Response(JSON.stringify({ error: 'Unauthorized' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    const cacheKey = new Request(url.toString(), { method: 'GET' });
    const cache = caches.default;

    let response = await cache.match(cacheKey);
    if (response) return response;

    // Cache miss — read from R2
    const object = await env.AQI_BUCKET.get(r2Key);
    if (!object) {
      return new Response(JSON.stringify({ error: 'Data not found' }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' }
      });
    }

    response = new Response(object.body, {
      headers: {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'public, max-age=900' // 15 min, matches cron interval
      }
    });

    ctx.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
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

    // Write observation data to R2
    await env.AQI_BUCKET.put('all_data.json', jsonData, {
      httpMetadata: { contentType: 'application/json' }
    });

    console.log(`Saved all_data.json with ${finalData.s.length} sensors. Total size: ${jsonData.length} bytes.`);

    // --- Forecast parsing ---
    const forecastMap = new Map<string, any>();

    for (const line of lines) {
      if (!line.trim()) continue;
      const parts = line.split('|');
      if (parts.length < 15) continue;

      const dataType = parts[5];
      const primary = parts[6];
      if (dataType !== 'F' || primary !== 'Y') continue;

      const reportingArea = parts[7];
      const stateCode = parts[8];
      const lat = parseFloat(parts[9]);
      const lon = parseFloat(parts[10]);
      if (isNaN(lat) || isNaN(lon)) continue;

      const name = stateCode.trim() ? `${reportingArea.trim()}, ${stateCode.trim()}` : reportingArea.trim();
      const validDate = parts[1]; // MM/DD/YY
      const recordSequence = parseInt(parts[4], 10);
      const parameterName = parts[11].trim();
      const aqiValueRaw = parts[12].trim();
      const aqiValue = aqiValueRaw ? parseInt(aqiValueRaw, 10) : null;
      const category = parts[13].trim() || null;
      const actionDay = parts[14].trim().toLowerCase() === 'yes';

      // Convert MM/DD/YY to YYYY-MM-DD
      const dp = validDate.split('/');
      if (dp.length !== 3) continue;
      let yr = parseInt(dp[2], 10);
      if (yr < 100) yr += 2000;
      const dateStr = `${yr}-${dp[0].padStart(2, '0')}-${dp[1].padStart(2, '0')}`;

      if (!forecastMap.has(name)) {
        forecastMap.set(name, { n: name, la: lat, lo: lon, f: [] });
      }

      forecastMap.get(name)!.f.push({
        d: dateStr,
        A: aqiValue !== null && !isNaN(aqiValue) ? aqiValue : null,
        C: category,
        p: parameterName,
        a: actionDay,
        _seq: recordSequence // used for sorting, stripped before output
      });
    }

    // Sort each location's forecasts by record sequence, then strip the sort key
    for (const loc of forecastMap.values()) {
      loc.f.sort((a: any, b: any) => a._seq - b._seq);
      loc.f = loc.f.map(({ _seq, ...rest }: any) => rest);
    }

    const forecastData = {
      t: Math.floor(Date.now() / 1000),
      s: Array.from(forecastMap.values())
    };

    const forecastJson = JSON.stringify(forecastData);
    await env.AQI_BUCKET.put('forecast_data.json', forecastJson, {
      httpMetadata: { contentType: 'application/json' }
    });

    console.log(`Saved forecast_data.json with ${forecastData.s.length} locations. Total size: ${forecastJson.length} bytes.`);

  } catch (err: any) {
    console.error("Error updating AQI data:", err.message);
  }
}
