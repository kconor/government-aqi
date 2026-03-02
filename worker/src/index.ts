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
    let latestTimestamp = "";
    
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
      
      // Create ISO 8601 timestamp (assuming roughly current year to construct it, though EPA gives "MM/DD/YY")
      // Example: "03/02/26", "14:00", "EST" -> We'll just pass the raw readable string to keep it simple, 
      // but include the date so the watch can check if it's "from a previous day"
      const timestampStr = `${validDate} ${validTime} ${timeZone}`;
      
      if (!locationsMap.has(name)) {
        locationsMap.set(name, {
          n: name,
          la: lat,
          lo: lon,
          m: {},
          t: timestampStr // Store the timestamp for this specific sensor
        });
      }
      
      const sensor = locationsMap.get(name)!;
      
      // Update sensor timestamp to the latest observation for this location
      if (timestampStr > sensor.t) {
          sensor.t = timestampStr;
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
      if (timestampStr > latestTimestamp) {
          latestTimestamp = timestampStr;
      }
    }
    
    const finalData = {
        t: latestTimestamp, // Global timestamp
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
