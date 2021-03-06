package me.pavelgeorgiev.songle.Activities

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import com.mikepenz.materialdrawer.Drawer
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem
import kotlinx.android.synthetic.main.activity_maps.*
import kotlinx.android.synthetic.main.lyrics_line.view.*
import me.pavelgeorgiev.songle.*
import me.pavelgeorgiev.songle.Objects.Placemark
import me.pavelgeorgiev.songle.Objects.Song
import me.pavelgeorgiev.songle.Parsers.KmlParser
import me.pavelgeorgiev.songle.R
import org.apmem.tools.layouts.FlowLayout
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException

/**
 * Activity contains map with placemarks words in a particular song.
 * Activity also contains expandable section, where user can guess the current song and view the progress made.
 * On some difficulties the activity also includes a countdown timer.
 */
@Suppress("DEPRECATION")
class MapsActivity :
        AppCompatActivity(),
        OnMapReadyCallback,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        LocationListener,
        DownloadFileCallback,
        NetworkReceiver.NetworkStateReceiverListener {

    private lateinit var mMap: GoogleMap
    private lateinit var mGoogleApiClient: GoogleApiClient
    private lateinit var mSongNumber: String
    private lateinit var mSongTitle: String
    private lateinit var mSong: Song
    private lateinit var mSongMapVersion: String
    private lateinit var mDrawer: Drawer
    private lateinit var kmlUrl: String
    private lateinit var lyricsUrl: String
    private lateinit var mReceiver: NetworkReceiver
    private lateinit var mNetworkSnackbar: Snackbar
    private lateinit var mErrorDialog: Dialog
    private lateinit var mDatabase: DatabaseReference
    private lateinit var mUser: FirebaseUser
    private lateinit var mDifficulty: String
    private var mLastPlacemarkLocation: LatLng? = null
    private var mCountdownTimer: CountDownTimer? = null
    private var mTimerMillisLeft = 0L
    private var mLyrics = LinkedHashMap<String, List<String>>()
    private var mLyricsViews = HashMap<String, FlowLayout>()
    private var mCurrLocationMarker: Marker? = null
    private var mCollectArea: Circle? = null
    private var mCollectedWords = HashMap<String, String>()
    private var mPlacemarks = HashMap<String, Placemark>()
    private var mSongGuessed = false

    private val PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1
    private var COLLECT_DISTANCE_THRESHOLD = 30
    private var TIMEOUT_SECONDS = 300
    val TAG = "MapsActivity"
    private val LOCATION_REQUEST_INTERVAL: Long = 5000
    private val LOCATION_REQUEST_FASTEST_INTERVAL: Long = 1000


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
                .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

//      Setup
        buildSnackbarAndDialogs()
        buildDrawerNav()
        buildSlidingPanel()
        buildGoogleApiClient()
        setupDifficulty()
        setupFirebase()
//        Start timer if difficulty requires one
        if (mDifficulty == getString(R.string.difficulty_very_hard) || mDifficulty == getString(R.string.difficulty_impossible)) {
            startTimer(TIMEOUT_SECONDS)
        } else {
            progressBar.visibility = View.GONE
        }

//       Setup network receiver
        mReceiver = NetworkReceiver()
        mReceiver.addListener(this)
        this.registerReceiver(mReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
    }

    /**
     * Setup references for Firebase Authentication and Firebase Realtime Database
     */
    private fun setupFirebase() {
        mUser = FirebaseAuth.getInstance().currentUser!!
        mDatabase = FirebaseDatabase
                .getInstance()
                .reference
                .child("users")
                .child(mUser.uid)
                .child("progress")
                .child("$mSongNumber-$mSongMapVersion")
    }

    /**
     * Get song object from the intent and setup class variables accordingly.
     */
    private fun setupDifficulty() {
        mSongMapVersion = intent.getStringExtra(getString(R.string.intent_song_map_version))
        mSong = intent.getParcelableExtra(getString(R.string.intent_song_object))
        mSongNumber = mSong.number
        mSongTitle = mSong.title

        val baseUrl = "${getString(R.string.maps_base_url)}/$mSongNumber"
        val mapVersion = "map$mSongMapVersion.kml"
        kmlUrl = "$baseUrl/$mapVersion"
        lyricsUrl = "$baseUrl/words.txt"

        when (mSongMapVersion) {
            "1" -> {
                mDifficulty = getString(R.string.difficulty_impossible)
                COLLECT_DISTANCE_THRESHOLD = 40
                TIMEOUT_SECONDS = 180
            }
            "2" -> {
                mDifficulty = getString(R.string.difficulty_very_hard)
                COLLECT_DISTANCE_THRESHOLD = 50
                TIMEOUT_SECONDS = 360
            }
            "3" -> {
                mDifficulty = getString(R.string.difficulty_hard)
                COLLECT_DISTANCE_THRESHOLD = 60
            }
            "4" -> {
                mDifficulty = getString(R.string.difficulty_medium)
                COLLECT_DISTANCE_THRESHOLD = 75
            }
            "5" -> {
                mDifficulty = getString(R.string.difficulty_easy)
                COLLECT_DISTANCE_THRESHOLD = 100
            }
        }
    }

    /**
     * Retrieves database entry for time left on the timer.
     * If timer hasn't been started before starts a new timer.
     * Otherwise starts a new timer with the remaining time.
     */
    private fun startTimer(seconds: Int) {
        mDatabase.child("timeLeft").addListenerForSingleValueEvent(object: ValueEventListener {
            override fun onCancelled(databaseError: DatabaseError?) {
                Log.w(TAG, "loadMapProgress:onCancelled", databaseError?.toException())
            }

            override fun onDataChange(snapshot: DataSnapshot) {
                progressBar.max = seconds
                if (snapshot.exists()) {
                    mTimerMillisLeft = snapshot.value as Long
                    mCountdownTimer = MyCountDownTimer(mTimerMillisLeft, 1000)
                    mCountdownTimer!!.start()
                } else {
                    AlertDialog.Builder(this@MapsActivity).setTitle("How about a challenge?")
                            .setMessage(getString(R.string.timeout_tooltip))
                            .setCancelable(false)
                            .setPositiveButton(R.string.play) { _, _ ->
                                mCountdownTimer = MyCountDownTimer(seconds.toLong() * 1000, 1000)
                                mCountdownTimer!!.start()
                            }
                            .setNeutralButton("Change difficulty", { _, _ ->
                                finish()
                            }).create()
                            .show()
                }
            }
        })

    }

    /**
     * Resets the currently running timer. Invoked on word collection.
     */
    private fun resetTimer(){
        mCountdownTimer?.cancel()
        mDatabase.child("timeLeft").removeValue()
        mCountdownTimer = MyCountDownTimer(TIMEOUT_SECONDS * 1000L, 1000)
        mCountdownTimer!!.start()
    }

    /**
     * Build snackbar and error dialogs for the activity
     */
    private fun buildSnackbarAndDialogs() {
        mNetworkSnackbar = Snackbar.make(
                findViewById(R.id.sliding_layout),
                "Network status: OFFLINE",
                Snackbar.LENGTH_INDEFINITE)

        mNetworkSnackbar.setAction("Dismiss", { mNetworkSnackbar.dismiss() })
        mNetworkSnackbar.setActionTextColor(resources.getColor(R.color.primaryColor))

        mErrorDialog =  AlertDialog.Builder(this).setTitle("No Internet Connection")
                .setMessage(getString(R.string.offline_disclaimer_general))
                .setPositiveButton(android.R.string.ok) { _, _ -> }.create()
    }

    override fun onStart() {
        super.onStart()
        mGoogleApiClient.connect()
    }

    override fun onResume() {
        super.onResume()
        onUiChange()
    }
    override fun onStop() {
        super.onStop()
        if (mGoogleApiClient.isConnected) {
            mGoogleApiClient.disconnect()
        }
        mCountdownTimer?.cancel()
    }

    override fun onPause() {
        super.onPause()

//        Stop location updates when Activity is not active
        if (mGoogleApiClient.isConnected) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this)
        }
        if(!mSongGuessed && mTimerMillisLeft > 0) {
            mDatabase.child("timeLeft").setValue(mTimerMillisLeft)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mReceiver)
    }

    /**
     * Retrieves the KML data from the network
     */
    private fun getKml() {
        if (NetworkReceiver.isNetworkConnected(this)) {
            DownloadFileService(this, DownloadFileService.KML_TYPE).execute(kmlUrl)
        } else {
            if(!mErrorDialog.isShowing){
                mErrorDialog.show()
            }
        }
    }

    /**
     * Retrieves the lyrics for the current song from the DB.
     * If entry is not available it retrieves the lyrics from the network.
     */
    private fun getLyricsFromDatabase(){
        mDatabase.addListenerForSingleValueEvent(object: ValueEventListener {
            override fun onCancelled(databaseError: DatabaseError?) {
                Log.w(TAG, "loadMapProgress:onCancelled", databaseError?.toException())
            }

            override fun onDataChange(snapshot: DataSnapshot) {
                mPlacemarks.clear()
                if (snapshot.exists() && snapshot.child("lyrics").exists()) {
                    for(line in snapshot.child("lyrics").children){
                        mLyrics.put(line.key, line.value as List<String>)
                    }
                } else {
                    getLyrics()
                }
                buildLyricsLayout()
            }
        })
    }

    /**
     * Retrieves the lyrics for the current song from the network.
     */
    private fun getLyrics() {
        if (NetworkReceiver.isNetworkConnected(this)) {
            DownloadFileService(this, DownloadFileService.TXT_TYPE).execute(lyricsUrl)
        } else {
            if(!mErrorDialog.isShowing){
                mErrorDialog.show()
            }
        }
    }

    /**
     * Retrieves all progress that could have been made on the song - words collected, placemarks.
     * Tries to retrieve from database. If entry not available tries to retrieve data from the network.
     */
    private fun getProgress() {
        mDatabase.addListenerForSingleValueEvent(object: ValueEventListener {
                    override fun onCancelled(databaseError: DatabaseError?) {
                        Log.w(TAG, "loadMapProgress:onCancelled", databaseError?.toException())
                    }

                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            for(word in snapshot.child("words").children){
                                mCollectedWords.put(word.key, word.value as String)
                            }

                            sliding_layout_header.text = buildWordsCollectedString(mCollectedWords.size)

                            for(entry in snapshot.child("markers").children){
                                val marker = Placemark.build(entry.value as HashMap<String, Any>)
                                mPlacemarks.put(marker.name, marker)
                                createMarker(marker, marker.location)
                            }
                            onMarkersLoaded()
                        } else {
                            getKml()
                        }
                    }
                })
    }


    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        //Initialize Google Play Services
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                //Location Permission already granted
                mMap.isMyLocationEnabled = true
            } else {
                //Request Location Permission
                checkLocationPermission()
            }
        } else {
            mMap.isMyLocationEnabled = true
        }

        // Add ”My location” button to the user interface
        mMap.uiSettings.isMyLocationButtonEnabled = true
    }

    /**
     * Invoked after file has been downloaded. Loads KML layout on the map.
     */
    @Throws(XmlPullParserException::class, IOException::class)
    override fun downloadComplete(bytes: ByteArray, fileType: String) {
        if (fileType == DownloadFileService.KML_TYPE) {
            onKmlDownload(bytes)
        }
        if (fileType == DownloadFileService.TXT_TYPE) {
            onTxtDownload(bytes)
        }
    }

    /**
     * Invoked after file has failed to download. Shows Alert dialog
     */
    override fun downloadFailed(errorMessage: String?, fileType: String) {
        AlertDialog.Builder(this).setTitle("Error")
                .setMessage("Oops! Something went wrong while downloading the necessary files.($errorMessage)")
                .setPositiveButton(R.string.try_again) { _, _ ->
                    if(fileType == DownloadFileService.KML_TYPE){
                        getKml()
                    }
                    if(fileType == DownloadFileService.TXT_TYPE){
                        getLyrics()
                    }}
                .setIcon(android.R.drawable.ic_dialog_alert).show()
        Log.d(TAG, errorMessage)
    }

    /**
     *  Callback for text file downloaded.
     */
    private fun onTxtDownload(bytes: ByteArray) {
        val inputStream = bytes.inputStream()

        inputStream.bufferedReader().useLines { lines ->
            lines.forEach {
                parseLine(it)
            }
        }
        inputStream.close()
        buildLyricsLayout()
        mDatabase.child("lyrics").setValue(mLyrics)
    }

    /**
     * Builds layout for displaying the collected lyrics.
     */
    private fun buildLyricsLayout() {
        mLyrics.forEach{ entry ->
            val lineView = LayoutInflater.from(this).inflate(R.layout.lyrics_line, null)
            var wordNum = 1
            entry.value.forEach {
                val textView = TextView(this)
                textView.text = it
                if(!mCollectedWords.containsKey("${entry.key}:$wordNum")){
                    textView.setBackgroundResource(R.color.secondaryText)
                    textView.setTextColor(resources.getColor(R.color.secondaryText))
                }

                val padding = CommonFunctions.dpToPx(3, this)
                textView.setPadding(padding,0,padding,0)
                lineView.lyrics_line_layout.addView(textView)
                wordNum++
            }
            mLyricsViews.put(entry.key, lineView as FlowLayout)
            lyrics_word_list.addView(lineView)
        }

    }

    /**
     * Parses a line from the lyrics file and stores the information in a HashMap.
     *
     * @param line line of the lyrics file
     */
    private fun parseLine(line: String) {
        val lineList = line.trim().split("\\s+".toRegex())
        val lineNumber: Int
        lineNumber = try {
            lineList[0].toInt()
        } catch (e: NumberFormatException) {
            Log.d(TAG, e.message)
            return
        }

        if (lineList.size < 2) {
            return
        }

        val lineWords = lineList.subList(1, lineList.size)
        mLyrics.put(lineNumber.toString(), lineWords)
    }

    /**
     * Callback for KML file being downloaded.
     *
     * @param bytes byte array of the file downloaded
     */
    private fun onKmlDownload(bytes: ByteArray) {
        val inputStream = bytes.inputStream()
        mPlacemarks = KmlParser().parse(inputStream, this)
        inputStream.close()
        mPlacemarks.forEach({createMarker(it.value, it.value.location)})
        onMarkersLoaded()
    }

    /**
     * Moves the camera to current location or last marker placed.
     * Saves markers and words collected in the database.
     */
    private fun onMarkersLoaded() {
        mMap.setOnMarkerClickListener { onMarkerClick(it) }

        if(mCurrLocationMarker != null){
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(mCurrLocationMarker!!.position, 18F))
        } else if(mLastPlacemarkLocation != null){
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(mLastPlacemarkLocation,18F))
        }
        saveProgress()
    }

    /**
     * Creates a marker depending on the classification of the word.
     *
     * @param placemark Placemark object containing information necessary to render the marker
     * @param location Location where the marker is to be placed
     */
    private fun createMarker(placemark: Placemark, location: LatLng){
        mLastPlacemarkLocation = location

        val markerOptions = MarkerOptions()
        markerOptions.position(location)
        markerOptions.title(placemark.name)
        markerOptions.snippet(placemark.description)

        when(placemark.styleId){
            Placemark.UNCLASSIFIED -> markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            Placemark.BORING -> markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
            Placemark.NOT_BORING -> markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            Placemark.INTERESTING -> markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            Placemark.VERY_INTERESTING -> markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET))
            else -> markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        }

        mMap.addMarker(markerOptions)

    }

    /**
     * If marker is in range - collects the word and deletes the marker.
     * Saves the collected word in the database.
     *
     * @param marker marker being clicked
     *
     * @return boolean showing if the marker has been collected
     */
    private fun onMarkerClick(marker: Marker): Boolean {
        if(marker.title == "Current Position" || mCurrLocationMarker == null){
            return false
        }
        val distance = FloatArray(1)
        distanceBetween(marker.position, mCurrLocationMarker!!.position,distance)
        println("DISTANCE -> ${distance[0]}")
        if(distance[0] > COLLECT_DISTANCE_THRESHOLD){
            return false
        }

        val wordCoord = marker.title.split(":").map { it.toInt() }
        val line = wordCoord[0].toString()
        val word = wordCoord[1] - 1



        Log.i("Marker click", "Marker clicked: " + marker.title)
        val locationInText = "Line: $line, position: ${wordCoord[1]}"

        collectWord(mLyrics[line]?.get(word), marker.title)
        mDatabase.child("markers").child(marker.title).removeValue()
        mPlacemarks.remove(marker.title)
        marker.remove()
        resetTimer()
        return true
    }

    /**
     * Calculates the distance between 2 LatLng objects
     *
     * @param point1 first point
     * @param point2 second point
     *
     * @return distance between the points
     */
    private fun distanceBetween(point1: LatLng, point2: LatLng, result: FloatArray){
        if(point1 == null || point2 == null){
            return
        }
        return Location.distanceBetween(
                point1.latitude,
                point1.longitude,
                point2.latitude,
                point2.longitude,
                result)
    }

    /**
     * Invoked once GoogleApiClient is connected.
     * If location permission is given request location update.
     */
    override fun onConnected(connectionHint: Bundle?) {
        val mLocationRequest = LocationRequest()
        mLocationRequest.interval = LOCATION_REQUEST_INTERVAL
        mLocationRequest.fastestInterval = LOCATION_REQUEST_FASTEST_INTERVAL
        mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        val permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)

        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, mLocationRequest, this)
        }
    }

    /**
     * Checks if location permission is given.
     * If that is not the case, it prompts the informs the user that location permission is required
     * and prompts them to give location permission to the app.
     */
    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {

                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
                AlertDialog.Builder(this)
                        .setTitle("Location Permission Needed")
                        .setMessage("This app needs the Location permission, please accept to use location functionality")
                        .setPositiveButton("OK", { _, _ ->
                            //Prompt the user once explanation has been shown
                            ActivityCompat.requestPermissions(this,
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                    PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION)
                        })
                        .create()
                        .show()

            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION)
            }
        }
    }

    /**
     * Handles the action of the user after he has been given a prompt for permission request
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty()
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    // Permission was granted. Do the location-related task you need to do.
                    if (ContextCompat.checkSelfPermission(this,
                            Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {

                        if (mGoogleApiClient == null) {
                            buildGoogleApiClient()
                        }
                        mMap.isMyLocationEnabled = true
                    }

                } else {
                    // Permission denied. Disable the functionality that depends on this permission.
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    /**
     * Puts the activity in immersive sticky mode
     *
     * @param hasFocus shows if window has focus
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /**
     * Brings the activity back in immersive mode after key press or keyboard pop up
     */
    private fun onUiChange() {
        val decorView = window.decorView
        decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        }
    }

    /**
     * Builds google API client
     */
    private fun buildGoogleApiClient() {
        mGoogleApiClient = GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build()
    }

    /**
     * Builds drawer navigation for the activity
     */
    private fun buildDrawerNav() {
        val item1 = PrimaryDrawerItem().
                withIdentifier(1)
                .withName("Songs")
                .withIcon(R.drawable.ic_music_note_black_24dp)
                .withSelectable(false)

        mDrawer = CommonFunctions.buildDrawerNav(arrayOf(item1), this)
                .withOnDrawerItemClickListener(Drawer.OnDrawerItemClickListener { _, position, _ ->
                when (position) {
                    1 -> {
                        startActivity(Intent(this, MainActivity::class.java))
                        return@OnDrawerItemClickListener true
                    }
                    else -> {
                        return@OnDrawerItemClickListener false
                    }
                }
            }).build()

        mDrawer.setSelection(-1)

        // Code makes sure the drawer doesn't brake the fullscreen mode
        if (Build.VERSION.SDK_INT in 19..20) {
            setWindowFlag(this, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, true)
        }
        if (Build.VERSION.SDK_INT >= 19) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (Build.VERSION.SDK_INT >= 21) {
            setWindowFlag(this, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, false)
            window.statusBarColor = Color.TRANSPARENT
        }


        if (Build.VERSION.SDK_INT >= 19) {
            mDrawer.drawerLayout.fitsSystemWindows = false
        }
        mapMenuButton.setOnClickListener({toggleMenu(it)})
    }


    /**
     * Used to keep activity in immersive mode.
     */
    private fun setWindowFlag(activity: Activity, bits: Int, on: Boolean) {
        val win = activity.window
        val winParams = win.attributes
        if (on) {
            winParams.flags = winParams.flags or bits
        } else {
            winParams.flags = winParams.flags and bits.inv()
        }
        win.attributes = winParams
    }


    /**
     * Builds the sliding panel, which contains the progress and guess song input field
     */
    private fun buildSlidingPanel() {
        sliding_layout_header.text = buildWordsCollectedString(mCollectedWords.size)
        song_name_input.setOnEditorActionListener({ view, actionId, _ ->
            var handled = false
            if((actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL)){
                guessSong(view.text.toString())
                handled = true
            }
            return@setOnEditorActionListener handled
        })
    }

    /**
     * Handles user try to guess the song
     *
     * @param songName user input
     */
    private fun guessSong(songName: String) {
        val dialog: Dialog

//      If user is correct
        if(mSongTitle.toLowerCase() == songName.toLowerCase()){
//            Clear all progress
            mDatabase.removeValue()
            mCollectedWords.clear()
            mSong.addCompletedDifficulty(mSongMapVersion)
            mSong.completed = true
            mSongGuessed = true
            mCountdownTimer?.cancel()

//            Save song as completed in the database
            val dbReference = FirebaseDatabase
                    .getInstance()
                    .reference
                    .child("users")
                    .child(FirebaseAuth.getInstance().uid)
                    .child("completed-songs")

            dbReference.addListenerForSingleValueEvent(object: ValueEventListener {
                override fun onCancelled(databaseError: DatabaseError?) {
                    Log.w(TAG, "loadMapProgress:onCancelled", databaseError?.toException())
                }

                override fun onDataChange(snapshot: DataSnapshot) {
                    mPlacemarks.clear()
                    if (snapshot.exists() && snapshot.child(mSong.title).child("difficultiesCompleted").exists()) {
                        for(difficulty in snapshot.child(mSong.title).child("difficultiesCompleted").children){
                            mSong.addCompletedDifficulty(difficulty.value as String)
                        }
                    }

                    val childUpdates = mutableMapOf<String, Song>()
                    childUpdates[mSong.title] = mSong
                    dbReference.updateChildren(childUpdates as Map<String, Any>?)
                }
            })

//          Build dialog for successful entry
            dialog = AlertDialog.Builder(this)
                    .setTitle("Success!")
                    .setCancelable(false)
                    .setMessage("Congratulations you guessed the song.")
                    .setNeutralButton("New Song", { _, _ ->
                        val intent = Intent(this, MainActivity::class.java)
                        intent.putExtra(getString(R.string.intent_song_object), mSong)
                        startActivity(intent)
                    })
                    .create()
        } else {
//          Build dialog for erroneous entry
            dialog = AlertDialog.Builder(this)
                    .setTitle("Wrong")
                    .setMessage("Keep trying you are close!")
                    .setNeutralButton("New Song", { _, _ ->
                        startActivity(Intent(this, MainActivity::class.java))
                    })
                    .setPositiveButton("Try again", { d, _ ->
                        d.dismiss()
                    })
                    .create()
        }
//          Show feedback to the user
        dialog.show()
    }

    /**
     * Toggles the drawer navigation menu
     */
    private fun toggleMenu(view: View) {
        if (mDrawer.drawerLayout.isDrawerOpen(Gravity.LEFT)) {
            mDrawer.closeDrawer()
        } else {
            mDrawer.openDrawer()
        }
    }

    /**
     * Marks a word as collected
     *
     * @param word word that has been collected
     * @param location location of word in text
     */
     private fun collectWord(word: String?, location: String) {
         if(word == null){
             return
         }
//       Put word as completed in the database
         val dbReference = mDatabase.child("words")
         val childUpdates = mutableMapOf<String, String>()
         childUpdates[location] = word
         dbReference.updateChildren(childUpdates as Map<String, String>)
//       Update class variables and number in "words collected" header
         mCollectedWords.put(location, word)
         sliding_layout_header.text = buildWordsCollectedString(mCollectedWords.size)

//         Uncover word in the lyrics view
         val wordCoord = location.split(":").map { it.toInt() }
         val line = wordCoord[0].toString()
         val word = wordCoord[1] - 1
         val lineLayout = mLyricsViews[line] as FlowLayout

         val textView = lineLayout.getChildAt(word) as TextView
         textView.setBackgroundResource(android.R.color.transparent)

     }

    /**
     * Saves markers and words collected to the database
     */
    private fun saveProgress(){
        mDatabase.child("markers").setValue(mPlacemarks)
        mDatabase.child("words").setValue(mCollectedWords)
    }

    /**
     * Builds correct string for singular word and multiple words collected
     */
    private fun buildWordsCollectedString(size: Int) : String{
        val word = if(size == 1) "word" else "words"
        return "$size $word collected"
    }

    override fun onConnectionSuspended(flag : Int) {
        println(">>>> Connection to Google APIs suspended. $TAG [onConnectionSuspended")
    }

    override fun onConnectionFailed(result : ConnectionResult) {
        // An unresolvable error has occurred and a connection to Google APIs
        // could not be established. Display an error message, or handle
        // the failure silently
        println(">>>> Connection to Google APIs could not be established. $TAG [onConnectionFailed")
    }

    /**
     * Changes the current location marker on location change
     *
     * @param location new location
     */
    override fun onLocationChanged(location: Location?) {
        if(location == null) {
            println("$TAG [onLocationChanged] Location unknown")
            return
        }

        if (mCurrLocationMarker != null) {
            mCurrLocationMarker!!.remove()
        }

        //Place current location marker
        val latLng = LatLng(location.latitude, location.longitude)
        val markerOptions = MarkerOptions()
        markerOptions.position(latLng)
        markerOptions.title("Current Position")
        markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
        mCurrLocationMarker = mMap.addMarker(markerOptions)
//      Move centre of word collection area to the new location
        if(mCollectArea == null) {
            val circleOptions = CircleOptions()
            val transparentColor = CommonFunctions.getColorWithAlpha(ContextCompat.getColor(this, R.color.secondaryColor), 40)
            circleOptions.center(latLng)
                    .radius(COLLECT_DISTANCE_THRESHOLD.toDouble())
                    .strokeColor(ContextCompat.getColor(this, R.color.secondaryColor))
                    .fillColor(transparentColor)
                    .strokeWidth(3.5F)
            mCollectArea = mMap.addCircle(circleOptions)
        } else {
            mCollectArea!!.center = latLng
        }


        //Move map camera
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17.7F))
    }

    /**
     * Callback from NetworkReceiver
     */
    override fun networkAvailable() {
        getSongData()
        mNetworkSnackbar.dismiss()
    }

    /**
     * Callback from NetworkReceiver
     */
    override fun networkUnavailable() {
        getSongData()
        mNetworkSnackbar.show()
    }

    /**
     * Retrieves lyrics and placemarks for current song
     */
    private fun getSongData() {
        if (mPlacemarks.isEmpty()) {
            getProgress()
        }
        if (mLyrics.isEmpty()) {
            getLyricsFromDatabase()
        }
    }

    /**
     * Class represent a countdown timer, which fills a progress bar.
     * Used only of some difficulties.
     *
     * @constructor Creates a new timer with given length and given tick period
     * @param millisInFuture length of timer
     * @param countDownInterval time between ticks
     */
    inner class MyCountDownTimer(millisInFuture: Long, countDownInterval: Long): CountDownTimer(millisInFuture, countDownInterval) {
        /**
         * Updates progress on every tick
         *
         * @param millisUntilFinished milliseconds until end of timer
         */
        override fun onTick(millisUntilFinished: Long) {

            val progress = progressBar.max - (millisUntilFinished/1000).toInt()
            mTimerMillisLeft = millisUntilFinished
            progressBar.progress = progress
        }

        /**
         * Triggered when timer finishes.
         * Alerts user that time is up and resets progress.
         */
        override  fun onFinish() {
            mDatabase.removeValue()
            mCollectedWords.clear()
            sliding_layout_header.text = buildWordsCollectedString(mCollectedWords.size)

            AlertDialog.Builder(this@MapsActivity).setTitle("Timeout")
                    .setMessage(getString(R.string.timeout_message))
                    .setCancelable(false)
                    .setPositiveButton(R.string.try_again) { _, _ ->
                        val intent = intent
                        finish()
                        startActivity(intent)
                    }
                    .setNeutralButton("Different song", { _, _ ->
                        startActivity(Intent(this@MapsActivity, MainActivity::class.java))
                    }).create()
                    .show()
        }
    }
}

