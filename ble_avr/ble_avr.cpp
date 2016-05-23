#define sonarSensorPin 2
#define buzzerPin 3
#define maxAlarmPulseWidth		1550    //Micro Seconds
#define minAlarmPulseWidth		500     //Micro Seconds
#define maxBuzzerRepPeriod		300     //Milli Seconds
#define minBuzzerRepPeriod		50      //Milli Seconds
#define buzzerActiveTime  		50      //Milli Seconds
#define minTimeToRegister 		5000	//Milli Seconds
#define maxRegisterPulseWidth   700	   	//Micro Seconds

boolean obstacleNear = false;
boolean enableRegister = false;
boolean countingToRegister = false;

unsigned long startTime = 0;
unsigned long sonarPWPeriod = 0;
unsigned long buzzerRepPeriod = 0;
unsigned long timeToRegister = 0;
unsigned long timeToRegisterStart = 0;

void setup() {                
  Serial.begin(9600);
  Serial.setTimeout(5);
  pinMode(buzzerPin, OUTPUT);     
  pinMode(sonarSensorPin, INPUT);
  digitalWrite (8, HIGH);
  digitalWrite (9, LOW);  
  attachInterrupt (0, sonarINT, CHANGE);
}

void loop() {

	if(enableRegister){
      Serial.println("register"); 
	  enableRegister = false;
	  timeToRegisterStart = millis();
	}

  
	if(obstacleNear){
	digitalWrite(buzzerPin, HIGH);
	delay(buzzerActiveTime);
	digitalWrite(buzzerPin, LOW);
	delay(buzzerRepPeriod);
	}
  
}

void sonarINT(){
  
    if(digitalRead(sonarSensorPin)){        			 //Rising edge of PWM sonar output 
      startTime=micros();                   			 //Start counting of active cycle of PWM sonar output
    }else{                                 				 //Falling edge of PWM sonar output
      sonarPWPeriod = micros()-startTime;				 //Get the difference between falling edge and rising edge
	 
      //Serial.println(sonarPWPeriod); 					 //Debugin

	 
	  if(sonarPWPeriod < maxAlarmPulseWidth){			 //Check if there is an obstacle near
	  
		if (sonarPWPeriod < maxRegisterPulseWidth){		 //Check if obstacle is closer than minimum distance to register 
		  if(!countingToRegister){						 //Start counting close to obstacle time
			timeToRegisterStart = millis();
			countingToRegister = true;
		  }else{
			if(millis()-timeToRegisterStart > minTimeToRegister){       //Check if counting time is bigger than minimum to register
				enableRegister = true;
				countingToRegister = false;
			}
		  }
		}else{
		  countingToRegister = false;
		}
				
		obstacleNear = true;
		
		if (sonarPWPeriod < minAlarmPulseWidth){
			buzzerRepPeriod = minBuzzerRepPeriod;
		}else{
			buzzerRepPeriod = map(sonarPWPeriod,minAlarmPulseWidth,maxAlarmPulseWidth,minBuzzerRepPeriod,maxBuzzerRepPeriod); //Translate PWM sensor period to alarm frequency
		}
		
      }else{               
		countingToRegister = false;	  
		obstacleNear = false;
      }
    }
}

