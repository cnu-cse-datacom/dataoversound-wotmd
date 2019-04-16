package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.support.annotation.NonNull;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;

import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.LinkedBlockingDeque;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;
    int ERROR_RANGE = 40;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;

    // 초기설정
    public Listentone(){
        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;      // HandShake 시작시 True설정
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();
    }

    // 패스트푸리에 변환함수
    private Double[] fftfreq(int len, int duratoin)
    {
        Double[] results = new Double[len] ;
        double val = 1.0 / (len * duratoin);
        int N = ((len-1)/2) + 1;
        int idx = 0;

        for(int i=0; i<N; i++) {
            results[idx++]=(double)(i)*val;
        }
        for(int i=(-(len/2)); i<0; i++){
            results[idx++]=(double)(i)*val;
        }

        return results;
    }

    // 배열에서 가장 큰 수가 있는 인덱스를 반환
    private int argmax(double[] arr){
        int max_idx = 0;            // 초기인덱스값은 0
        double max_value = arr[0];  // 초기 맥스값

        for(int i = 0; i < arr.length; i++){
            if (arr[i] > max_value){    // 더 큰것을 찾으면 max_value와 max_idx값을 바꿔준다.
                max_value = arr[i];
                max_idx = i;
            }
        }
        return max_idx; // 가장 큰 value의 idx값을 바꿔준다.
    }

    private double findFrequency(double[] toTransform) {
        int len = toTransform.length;
        double[] real = new double[len];
        double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];

        Complex[] complx = transform.transform(toTransform, TransformType.FORWARD);
        Double[] freq = this.fftfreq(complx.length, 1);

        for(int i=0; i<complx.length; i++){
            realNum = complx[i].getReal();      // 실수 부분
            imgNum = complx[i].getImaginary();  // 허수 부분
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum));
        }
        // argmax가 필요하여 함수로 만들어서 사용
        // argmax는 배열내에서 가장 큰값이 잇는 인덱스를 반환해준다.
        int peak_coeff = argmax(mag);
        double peak_freq = freq[peak_coeff].doubleValue();  // peak_freq를 반환

        return (Math.abs(peak_freq * mSampleRate)); // dominant freq반환
    }

    // 가장 가까운 2의 제곱수를 찾아준다.
    int findPowerSize(int number){
        int powersize=1;
        for(int i=0; i<number; i++){
            if(number <= powersize) // 주어진 숫자보다 powersize가 커지면 탈출
                break;
            powersize *=2;
        }
        return (Math.abs(powersize/2 - number) < Math.abs(powersize - number) ? powersize/2 : powersize);   // 3항연산자를 사용하여 가까운 제곱수를 반환한다.
    }
    Character[] decode_bitchunks(Integer[] chunks) {
        LinkedList<Character> out_bytes = new LinkedList<Character>();
        char one_byte;
        String result = "";

        // Reed Solomon은 사용하지않을것이지 때문에 뒤의 4바이트는 계산하지않았습니다.
        for(int i=0; i< chunks.length-8; i+=2){
            Log.d("Listenton RawUpper", Integer.toString(chunks[i].intValue()));    // 상위 4비트로 쓰일부분
            Log.d("Listenton RawLower", Integer.toString(chunks[i+1].intValue()));  // 하위 4비트로 쓰일부분
            one_byte = (char)(( (chunks[i].intValue() << 4) | (chunks[i+1].intValue() & 0xF) ) & 0xFF); // 합쳐서 1바이트로 만든다.
            out_bytes.add(one_byte);
            Log.d("Listenton decodeData", Integer.toString((int)one_byte));
            Log.d("Listenton CharacterData", ""+one_byte);  // 디코드된 문자를 출력
            result+=one_byte;
            Log.d("Listenton StringData", result);  // 합쳐서 출력시켜줌
        }

        return (out_bytes.toArray(new Character[out_bytes.size()]));
    }

    Character[] extract_packet(Integer[] freqs){
        LinkedList<Integer> bit_chunks = new LinkedList<Integer>();
        int bit_chunk;
        int pre_freq=0; // 주파수 중복을 피하기위한 이전 주파수 저장
        int i=0;

        while(freqs[i].intValue() >= HANDSHAKE_START_HZ-8 && freqs[i].intValue() <= HANDSHAKE_START_HZ+8){
            i++;
        }
        for(; i<freqs.length; i++){
            if(pre_freq==freqs[i])  // 이전에 받은 주파수와 같다면, 다음 주파수를 받는다.
                continue;

            bit_chunk = Math.round((freqs[i].intValue() + ERROR_RANGE - START_HZ) / STEP_HZ);   // bit_chunk로 변환
            if(0 <= bit_chunk && bit_chunk < (int)(Math.pow(2, BITS))) {    // 0<= bit_chunk <= 2^4 인지 판별
                bit_chunks.add(bit_chunk);  // bit_chunks에 추가
            }
            pre_freq = freqs[i];    // pre_freq에 추가하여 중복을 피한다.
        }

        return decode_bitchunks(bit_chunks.toArray(new Integer[bit_chunks.size()]));
    }

    // decode.py 의 listen_linux와 같은 부분
    public void PreRequest() {
        LinkedList<Integer> packet = new LinkedList<Integer>();

        while(true) {
            int blocksize = findPowerSize((int) (long) Math.round(interval / 2 * mSampleRate));   // 반올림한 정수값과 가장 가까운 2의 제곱수 반환
            short[] buffer = new short[blocksize];
            // buffer에 소리데이터를 blocksize만큼 읽어드림. 읽어들인만큼 반환
            int bufferedReadResult = mAudioRecord.read(buffer, 0, blocksize);

            //Log.d("Listentone_read", Integer.toString((int)bufferedReadResult));

            if(bufferedReadResult <= 0) // 읽은게 없다면 다시 읽기
                continue;

            double[] chunks = new double[blocksize];
            for(int i=0; i<bufferedReadResult; i++){
                chunks[i]=buffer[i];
            }
            for(int i=0; i<blocksize-bufferedReadResult; i++){
                chunks[i]=0;
            }

            double dominant = findFrequency(chunks);
            Log.d("Listentone_dominant", Double.toString(dominant));


            if(startFlag && (dominant >= HANDSHAKE_END_HZ-8 && dominant <= HANDSHAKE_END_HZ+8)){
                Log.d("Listentone End", "HandShake End");
                Character[] byte_stream = extract_packet(packet.toArray(new Integer[packet.size()]));
                String result = "";

                for(int i=0; i<byte_stream.length; i++){
                    result += byte_stream[i].charValue();
                }
                Log.d("Listentone result", result);
                break;
            }
            else if(startFlag){
                packet.add((int)dominant);
            }
            else if((dominant >= HANDSHAKE_START_HZ-8 && dominant <= HANDSHAKE_START_HZ+8)){
                startFlag = true;
                Log.d("Listentone Start", "HandShake Start");
            }

            //startFlag
        }
    }
}
