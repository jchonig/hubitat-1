/**
 * Laundry Manager app for Hubitat
 *
 * Copyright (c) 2019 Justin Walker
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 *
 *  v1.0.0 - Initial version
 *  v1.0.1 - Made washer, dryer, and reset button optional
 *  v1.0.2 - Allow multiple notification devices
 *  v1.0.3 - Added support for contact sensors, TTS, time-based auto reset, and device labels (2021-01-17).
 *
 */

definition(
    name: 'Laundry Manager',
    namespace: 'augoisms',
    author: 'augoisms',
    description: 'Monitor the laundry status and send a notification when the cycle is complete',
    category: 'Convenience',
    iconUrl: '',
    iconX2Url: '',
    singleInstance: true
)

preferences {
    page(name: 'settings')
}

def settings() {
    dynamicPage(name: 'settings', title: " ", install: true, uninstall: true) {
        section('<b>Machines</b><hr>') {
            input 'washerMeter', 'capability.powerMeter', title: 'Washer Power Meter'
            input 'dryerMeter', 'capability.powerMeter', title: 'Dryer Power Meter'
        }
        section('<b>Reset</b><hr>') {
            paragraph '<div><i>Automatically reset any finished machine back to idle by subscribing to a button or contact sensor.</i></div>'
            input 'resetButton', 'capability.pushableButton', title: 'Button'
            input 'resetContacts', 'capability.contactSensor', title: 'Contact Sensors', multiple: true
            input 'resetAuto', 'bool', title: 'Time-based auto reset?', defaultValue: false, submitOnChange: true
            if (resetAuto) input 'resetAutoTime', 'number', defaultValue: 60, title: 'Time before resetting (minutes)'
        }
        section( '<b>Notifications</b><hr>' ) {
            input 'sendPushMessage', 'bool', title: 'Send a push notification?', defaultValue: false, submitOnChange: true
            if (sendPushMessage) input 'notificationDevices', 'capability.notification', title: 'Notification device', required: false, multiple: true

            input 'sendTTS', 'bool', title: 'Send a TTS notification?', defaultValue: false, submitOnChange: true
            if (sendTTS) input 'ttsDevices', 'capability.speechSynthesis', title: 'Choose speaker(s)', required: false, multiple: true

            if (sendPushMessage || sendTTS) {
                input 'repeat', 'bool', title: 'Repeat notifications?'
                if (repeat) input 'repeatInterval', 'number', title: 'Repeat interval (minutes)', defaultValue: 15, submitOnChange: true
                input 'modes', 'mode', title: 'Only send notifications in specific modes', multiple: true, required: false
            }
        }

        section('<hr>') {
            input name: 'loggingEnabled', type: 'bool', title: 'Enable Logging?', defaultValue: false
            paragraph '<div><i>Automatically disables after 30 minutes.</i></div><br>'
        }
    }
}

def installed() {
	init()
}

def updated() {
	unsubscribe()
	init()
}

def uninstalled() {
	removeChildDevices(getChildDevices())
}

void init() {
    // disable logging in 30 minutes
    if (loggingEnabled) runIn(1800, disableLogging)

    // subscribe to device events
    washerMeter && subscribe(washerMeter, 'power', washerPowerHandler)
    dryerMeter && subscribe(dryerMeter, 'power', dryerPowerHandler) 
    resetButton && subscribe(resetButton, 'pushed', resetHandler)
    resetContacts && subscribe(resetContacts, 'contact', resetHandler)
	 
    createChildren()
}

///
/// management of child devices
///

void createChildren() {
    logDebug 'creating Laundry Manager children'
    
    washerMeter && createChild('laundry-machine-washer', 'Laundry Washer')
    dryerMeter && createChild('laundry-machine-dryer', 'Laundry Dryer')
}

void createChild(dni, label) {
    def child = getChildDevice(dni)
    if (child) {
        logDebug "Device (${dni}) already exists"
        subscribeToChild(child)
	} else {
        try {
        	logDebug 'attempting to create child device'
            def newChild = addChildDevice('augoisms', 'Laundry Machine', dni, null, [name: label])
            log.trace "created ${newChild.displayName} with id $dni"
            subscribeToChild(newChild)
        }
        catch (Exception e) {
        	log.warn 'error creating child device'
        	log.trace e
        }
    	
	}   
}

void subscribeToChild(child){    
    if (child.deviceNetworkId == 'laundry-machine-washer') {
        subscribe(child, 'status', washerStatusHandler)
        washerPowerHandler()
    }
    
    if (child.deviceNetworkId == 'laundry-machine-dryer') {
        subscribe(child, 'status', dryerStatusHandler)
        dryerPowerHandler()
    }
}

void removeChildDevices(delete) {
	delete.each {deleteChildDevice(it.deviceNetworkId)}
}

///
/// event handlers
///

void resetHandler(evt) {
	logDebug 'button pushed'
    
    def washer = getChildDevice('laundry-machine-washer')
    if(washer.currentValue('status') == 'finished') {
    	logDebug 'resetting washer'
        washer.resetFinished()
    }
    
    def dryer = getChildDevice('laundry-machine-dryer')
    if(dryer.currentValue('status') == 'finished') {
    	logDebug 'resetting dryer'
        dryer.resetFinished()
    }
}

void washerStatusHandler(evt) {
	logDebug 'checking washer status'  
    def washer = getChildDevice('laundry-machine-washer')
    String name = washer.getLabel() ?: 'Washer'
    statusCheck(washer, name, 'washerStatusHandler')
}

void dryerStatusHandler(evt) {
	logDebug 'checking dryer status'   
    def dryer = getChildDevice('laundry-machine-dryer')
    String name = washer.getLabel() ?: 'Dryer'
    statusCheck(dryer, name, 'dryerStatusHandler')
}

void washerPowerHandler(evt) {
    def watts = washerMeter.currentValue('power')
    def washer = getChildDevice('laundry-machine-washer')
    washer.updatePower(watts)
}

void dryerPowerHandler(evt) {
    def watts = dryerMeter.currentValue('power')
    def dryer = getChildDevice('laundry-machine-dryer')
    dryer.updatePower(watts)
}

///
/// other methods
///

void disableLogging() {
	log.info 'Logging disabled.'
	device.updateSetting('loggingEnabled',[value:'false',type:'bool'])
}

void logDebug(str) {
    if (loggingEnabled) {
        log.debug str
    }
}

void send(msg) {
    // push notifications
	def modeOkay = !modes || modes.contains(location.mode)
    Boolean push = sendPushMessage == true || sendPushMessage == 'Yes'
    if (push && modeOkay) {
		logDebug 'sending push message' 		
        notificationDevices.each{ device -> 
            device.deviceNotification(msg)
        }
	}

    // tts devices
    def modeTTSOkay = !modesTTS || modesTTS.contains(location.mode)
    if (sendTTS && modeTTSOkay) {
        logDebug 'sending TTS message'
        ttsDevices.each{ device ->
            device.speak(msg)
        }
    }
    
    logDebug msg
}

void statusCheck(device, deviceName, handlerName) {
	def status = device.currentValue('status')
  
    if (status == 'finished') {
    	// send notification
        send("${deviceName} is finished")

        // schedule repeat notification
        if(repeat) {
        	logDebug 'scheduling a repeat notification'
            runIn(repeatInterval * 60, handlerName)
        }

        // auto reset
        if (resetAuto) {
            runIn((resetAutoTime ?: 60) * 60, resetHandler)
        }
    }
}