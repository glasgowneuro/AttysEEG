# plots all channels of the Attys as recorded by AttysScope2
# www.attys.tech
#
import numpy as np
import pylab as pl
#
data = np.loadtxt('eeg1.tsv');
#
fig = pl.figure(1);
#
a = 0;
b = len(data)-1;
# xacc
pl.subplot(811);
pl.plot(data[a:b,0],data[a:b,2]);
pl.xlabel('time/sec');
pl.ylabel('EEG / V');
#
pl.subplot(812);
pl.plot(data[a:b,0],data[a:b,3]);
pl.xlabel('time/sec');
pl.ylabel('delta / V');
#
# xmag
pl.subplot(813);
pl.plot(data[a:b,0],data[a:b,4]);
pl.xlabel('time/sec');
pl.ylabel('theta / V');
#
# ymag
pl.subplot(814);
pl.plot(data[a:b,0],data[a:b,5]);
pl.xlabel('time/sec');
pl.ylabel('alpha / V');
# zmag
pl.subplot(815);
pl.plot(data[a:b,0],data[a:b,6]);
pl.xlabel('time/sec');
pl.ylabel('beta / V');
#
pl.subplot(816);
pl.plot(data[a:b,0],data[a:b,7]);
pl.xlabel('time/sec');
pl.ylabel('gamma / V');
#
pl.show();
#
